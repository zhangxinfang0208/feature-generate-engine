package com.example.featuredag.runtime;

import com.example.featuredag.operator.SequenceKeyDomain;
import com.example.featuredag.physical.PhysicalNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * “按 key 过滤序列后计数”的通用融合执行器。
 *
 * <p>它把原本需要为每个候选创建序列视图再聚合的两步计算，转换为“每个请求序列建一次索引，
 * 每个唯一候选 key 查一次计数”。执行器只认识 {@link SequenceKeyDomain} 和注册的索引提供者，
 * 不依赖触发融合的业务算子名称，符合 C10 的注册式扩展约束。</p>
 */
public final class SequenceKeyCountExecutor implements PhysicalExecutor {
    // groupIndex 与具体 SequenceValue 一起进入 key，防止在线批中不同请求或不同序列视图互相污染。
    private record SequenceIndexCacheKey(
            int groupIndex,
            SequenceKeyDomain keyDomain,
            SequenceValue sequence) {}

    private record SequenceKeyCountCacheKey(
            int groupIndex,
            SequenceKeyDomain keyDomain,
            SequenceValue sequence,
            Object key) {}

    private final SequenceIndexRegistry indexRegistry;

    public SequenceKeyCountExecutor(SequenceIndexRegistry indexRegistry) {
        this.indexRegistry = Objects.requireNonNull(indexRegistry, "indexRegistry");
    }

    @Override
    public void validate(PhysicalNode node) {
        // 初始化阶段提前验证 key 域已有索引实现，避免请求执行到一半才发现专用执行器不可用。
        indexRegistry.require(keyDomain(node));
    }

    @Override
    public ValueHandle execute(
            PhysicalNode node,
            ExecutionContext context,
            RuntimeNodeState state) {
        if (node.inputSlots().size() != 2) {
            throw new IllegalStateException("sequence-key-count requires sequence and key inputs");
        }
        SequenceKeyDomain keyDomain = keyDomain(node);
        SequenceIndexProvider provider = indexRegistry.require(keyDomain);
        ValueHandle sequenceHandle = requireSlot(context, node.inputSlots().get(0));
        ValueHandle keyHandle = requireSlot(context, node.inputSlots().get(1));
        // 在线分组批的序列按请求组变化，需要逐组建索引；单请求则可直接复用下面的统一候选向量路径。
        if (context.isOnlineBatch()) {
            return executeOnlineBatch(
                    node, context, state, keyDomain, provider, sequenceHandle, keyHandle);
        }

        Object sequenceRaw = sequenceHandle.raw();
        EvaluationFailure sequenceFailure = inheritedFailure(sequenceRaw);
        if (sequenceFailure != null) {
            return new CandidateVectorValue(repeatedFailure(
                    context.candidateCount(), sequenceFailure));
        }
        if (!(sequenceRaw instanceof SequenceValue sequence)) {
            throw new IllegalArgumentException("First input must be SequenceValue");
        }
        List<Object> rawKeys = toCandidateValues(
                keyHandle, context.candidateCount());
        List<Object> normalizedKeys = new ArrayList<>(
                java.util.Collections.nCopies(rawKeys.size(), null));
        boolean[] healthyKeys = new boolean[rawKeys.size()];
        List<Object> result = new ArrayList<>(
                java.util.Collections.nCopies(rawKeys.size(), null));
        Set<Object> uniqueKeys = new LinkedHashSet<>();
        for (int candidateIndex = 0; candidateIndex < rawKeys.size(); candidateIndex++) {
            Object rawKey = rawKeys.get(candidateIndex);
            EvaluationFailure inherited = inheritedFailure(rawKey);
            if (inherited != null) {
                result.set(candidateIndex, inherited);
                continue;
            }
            try {
                Object normalized = provider.normalizeQueryKey(rawKey);
                normalizedKeys.set(candidateIndex, normalized);
                healthyKeys[candidateIndex] = true;
                uniqueKeys.add(normalized);
            } catch (RuntimeException error) {
                result.set(candidateIndex, newFailure(node, context, candidateIndex, error));
                state.addOperatorFailures(1);
            }
        }

        // 候选 key 先规范化再去重，保证语义等价的输入只做一次索引查询。
        state.setDedupCounts(normalizedKeys.size(), uniqueKeys.size());
        if (uniqueKeys.isEmpty()) return new CandidateVectorValue(result);

        SequenceIndexCacheKey indexCacheKey = new SequenceIndexCacheKey(0, keyDomain, sequence);
        IndexValue index;
        // 一级缓存复用序列索引，避免同一请求的多个融合节点重复扫描相同序列视图。
        RuntimeCache.CacheLookup cachedIndex = context.runtimeCache().lookup(
                CacheKind.SEQUENCE_INDEX, indexCacheKey, state);
        if (cachedIndex.hit()) {
            if (!(cachedIndex.value() instanceof IndexValue cached)) {
                throw new IllegalStateException("Sequence index cache contains an incompatible value");
            }
            index = cached;
        } else {
            try {
                index = provider.build(sequence);
            } catch (RuntimeException error) {
                int failureCount = 0;
                for (int candidateIndex = 0; candidateIndex < healthyKeys.length; candidateIndex++) {
                    if (!healthyKeys[candidateIndex]) continue;
                    result.set(candidateIndex, newFailure(node, context, candidateIndex, error));
                    failureCount++;
                }
                state.addOperatorFailures(failureCount);
                return new CandidateVectorValue(result);
            }
            context.runtimeCache().put(
                    CacheKind.SEQUENCE_INDEX, indexCacheKey, index, state);
        }

        Map<Object, Integer> countsByKey = new LinkedHashMap<>();
        Map<Object, RuntimeException> failuresByKey = new LinkedHashMap<>();
        // 二级缓存保存具体 key 的聚合结果；即使索引实现昂贵查询，也只为唯一 key 计算一次。
        for (Object key : uniqueKeys) {
            SequenceKeyCountCacheKey countCacheKey =
                    new SequenceKeyCountCacheKey(0, keyDomain, sequence, key);
            RuntimeCache.CacheLookup cachedCount = context.runtimeCache().lookup(
                    CacheKind.SEQUENCE_COUNT, countCacheKey, state);
            if (cachedCount.hit()) {
                countsByKey.put(key, (Integer) cachedCount.value());
            } else {
                try {
                    int count = index.count(key);
                    context.runtimeCache().put(
                            CacheKind.SEQUENCE_COUNT, countCacheKey, count, state);
                    countsByKey.put(key, count);
                } catch (RuntimeException error) {
                    failuresByKey.put(key, error);
                }
            }
        }

        // 按原候选 key 顺序 scatter，恢复与输入候选一一对应的输出向量。
        int queryFailureCount = 0;
        for (int candidateIndex = 0; candidateIndex < normalizedKeys.size(); candidateIndex++) {
            if (!healthyKeys[candidateIndex]) continue;
            Object key = normalizedKeys.get(candidateIndex);
            RuntimeException error = failuresByKey.get(key);
            if (error == null) {
                result.set(candidateIndex, countsByKey.get(key));
            } else {
                result.set(candidateIndex, newFailure(node, context, candidateIndex, error));
                queryFailureCount++;
            }
        }
        state.addOperatorFailures(queryFailureCount);
        return new CandidateVectorValue(result);
    }

    private ValueHandle executeOnlineBatch(
            PhysicalNode node,
            ExecutionContext context,
            RuntimeNodeState state,
            SequenceKeyDomain keyDomain,
            SequenceIndexProvider provider,
            ValueHandle sequenceHandle,
            ValueHandle keyHandle) {
        List<Object> result = new ArrayList<>(
                java.util.Collections.nCopies(context.candidateCount(), null));
        int totalUniqueKeys = 0;
        // 每个组拥有独立共享序列和候选区间，缓存 key 也带 groupIndex，组间不会错误复用。
        for (int groupIndex = 0; groupIndex < context.onlineGroupCount(); groupIndex++) {
            Object sequenceRaw = requestValue(sequenceHandle, groupIndex);
            int start = context.candidateGroupStart(groupIndex);
            int end = context.candidateGroupEnd(groupIndex);
            // 零候选组不产生输出元素，但仍保持 offsets 对后续组的正确定位。
            if (start == end) continue;
            EvaluationFailure sequenceFailure = inheritedFailure(sequenceRaw);
            if (sequenceFailure != null) {
                for (int candidateIndex = start; candidateIndex < end; candidateIndex++) {
                    result.set(candidateIndex, sequenceFailure);
                }
                continue;
            }
            if (!(sequenceRaw instanceof SequenceValue sequence)) {
                throw new IllegalArgumentException(
                        "First input must be SequenceValue for online batch group "
                                + groupIndex + " ("
                                + context.onlineGroupExecutionId(groupIndex) + ")");
            }
            List<Object> normalizedKeys = new ArrayList<>(
                    java.util.Collections.nCopies(end - start, null));
            boolean[] healthyKeys = new boolean[end - start];
            Set<Object> uniqueKeys = new LinkedHashSet<>();
            for (int candidateIndex = start; candidateIndex < end; candidateIndex++) {
                Object rawKey = candidateValue(keyHandle, candidateIndex, groupIndex);
                EvaluationFailure inherited = inheritedFailure(rawKey);
                if (inherited != null) {
                    result.set(candidateIndex, inherited);
                    continue;
                }
                try {
                    Object normalized = provider.normalizeQueryKey(rawKey);
                    int localIndex = candidateIndex - start;
                    normalizedKeys.set(localIndex, normalized);
                    healthyKeys[localIndex] = true;
                    uniqueKeys.add(normalized);
                } catch (RuntimeException error) {
                    result.set(candidateIndex, newFailure(node, context, candidateIndex, error));
                    state.addOperatorFailures(1);
                }
            }
            totalUniqueKeys += uniqueKeys.size();
            if (uniqueKeys.isEmpty()) continue;

            // 同一组只构建一次序列索引；sequence 对象包含具体选择视图，缓存不会跨视图误命中。
            SequenceIndexCacheKey indexCacheKey =
                    new SequenceIndexCacheKey(groupIndex, keyDomain, sequence);
            IndexValue index;
            RuntimeCache.CacheLookup cachedIndex = context.runtimeCache().lookup(
                    CacheKind.SEQUENCE_INDEX, indexCacheKey, state);
            if (cachedIndex.hit()) {
                if (!(cachedIndex.value() instanceof IndexValue cached)) {
                    throw new IllegalStateException(
                            "Sequence index cache contains an incompatible value");
                }
                index = cached;
            } else {
                try {
                    index = provider.build(sequence);
                } catch (RuntimeException error) {
                    int failureCount = 0;
                    for (int localIndex = 0; localIndex < healthyKeys.length; localIndex++) {
                        if (!healthyKeys[localIndex]) continue;
                        int candidateIndex = start + localIndex;
                        result.set(candidateIndex, newFailure(
                                node, context, candidateIndex, error));
                        failureCount++;
                    }
                    state.addOperatorFailures(failureCount);
                    continue;
                }
                context.runtimeCache().put(
                        CacheKind.SEQUENCE_INDEX, indexCacheKey, index, state);
            }

            Map<Object, Integer> countsByKey = new LinkedHashMap<>();
            Map<Object, RuntimeException> failuresByKey = new LinkedHashMap<>();
            // 查询结果先按唯一 key 收集，随后按 normalizedKeys 顺序追加，保持组内候选顺序不变。
            for (Object key : uniqueKeys) {
                SequenceKeyCountCacheKey countCacheKey = new SequenceKeyCountCacheKey(
                        groupIndex, keyDomain, sequence, key);
                RuntimeCache.CacheLookup cachedCount = context.runtimeCache().lookup(
                        CacheKind.SEQUENCE_COUNT, countCacheKey, state);
                if (cachedCount.hit()) {
                    countsByKey.put(key, (Integer) cachedCount.value());
                } else {
                    try {
                        int count = index.count(key);
                        context.runtimeCache().put(
                                CacheKind.SEQUENCE_COUNT, countCacheKey, count, state);
                        countsByKey.put(key, count);
                    } catch (RuntimeException error) {
                        failuresByKey.put(key, error);
                    }
                }
            }
            int queryFailureCount = 0;
            for (int localIndex = 0; localIndex < normalizedKeys.size(); localIndex++) {
                if (!healthyKeys[localIndex]) continue;
                int candidateIndex = start + localIndex;
                Object key = normalizedKeys.get(localIndex);
                RuntimeException error = failuresByKey.get(key);
                if (error == null) {
                    result.set(candidateIndex, countsByKey.get(key));
                } else {
                    result.set(candidateIndex, newFailure(
                            node, context, candidateIndex, error));
                    queryFailureCount++;
                }
            }
            state.addOperatorFailures(queryFailureCount);
        }
        state.setDedupCounts(context.candidateCount(), totalUniqueKeys);
        return CandidateBatchValue.owned(result, node.logicalValueShape());
    }

    private static Object requestValue(ValueHandle handle, int groupIndex) {
        // 序列输入允许请求批或全批广播值，禁止误用候选/离线句柄破坏“一组一序列”的前提。
        if (handle instanceof RequestBatchValue batch) return batch.valueAt(groupIndex);
        if (handle instanceof CandidateBatchValue || handle instanceof CandidateVectorValue
                || handle instanceof OfflineBatchValue) {
            throw new IllegalArgumentException(
                    "Sequence input must be request-scoped in online batch execution");
        }
        return handle.raw();
    }

    private static Object candidateValue(
            ValueHandle handle,
            int candidateIndex,
            int groupIndex) {
        // key 通常来自 CandidateBatchValue；请求级或标量 key 也可按组/全批广播。
        if (handle instanceof CandidateBatchValue batch) return batch.valueAt(candidateIndex);
        if (handle instanceof RequestBatchValue batch) return batch.valueAt(groupIndex);
        if (handle instanceof CandidateVectorValue || handle instanceof OfflineBatchValue) {
            throw new IllegalArgumentException(
                    "Candidate input has incompatible runtime value handle");
        }
        return handle.raw();
    }

    private static ValueHandle requireSlot(ExecutionContext context, String slot) {
        ValueHandle value = context.resultSlots().get(slot);
        if (value == null) throw new IllegalStateException("Input slot not available: " + slot);
        return value;
    }

    private static SequenceKeyDomain keyDomain(PhysicalNode node) {
        Object value = node.executorConfig().get("keyDomain");
        if (value == null) {
            throw new IllegalArgumentException("sequence-key-count requires keyDomain config");
        }
        return new SequenceKeyDomain(String.valueOf(value));
    }

    private static List<Object> toCandidateValues(ValueHandle handle, int candidateCount) {
        if (handle instanceof CandidateVectorValue vector) return vector.values();
        // 非候选句柄按候选数广播，兼容常量或请求级 key，同时保持输出行数不变。
        List<Object> result = new ArrayList<>(candidateCount);
        for (int index = 0; index < candidateCount; index++) result.add(handle.raw());
        return result;
    }

    private static EvaluationFailure inheritedFailure(Object value) {
        return value instanceof EvaluationFailure failure ? failure : null;
    }

    private static List<Object> repeatedFailure(int count, EvaluationFailure failure) {
        return new ArrayList<Object>(java.util.Collections.nCopies(count, failure));
    }

    private static EvaluationFailure newFailure(
            PhysicalNode node,
            ExecutionContext context,
            int candidateIndex,
            RuntimeException error) {
        return EvaluationFailure.batch(
                node.physicalNodeId(),
                candidateLocation(context, candidateIndex),
                error);
    }

    private static String candidateLocation(
            ExecutionContext context,
            int candidateIndex) {
        if (!context.isOnlineBatch()) return "candidate " + candidateIndex;
        int groupIndex = context.candidateGroupIndex(candidateIndex);
        return "online batch group " + groupIndex + " ("
                + context.onlineGroupExecutionId(groupIndex) + "), candidate "
                + context.candidateIndexInGroup(candidateIndex);
    }
}
