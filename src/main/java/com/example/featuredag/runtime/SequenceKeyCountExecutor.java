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

/** “按 key 过滤序列后计数”的通用融合执行器。 */
public final class SequenceKeyCountExecutor implements PhysicalExecutor {
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
        if (context.isOnlineBatch()) {
            return executeOnlineBatch(
                    node, context, state, keyDomain, provider, sequenceHandle, keyHandle);
        }

        Object sequenceRaw = sequenceHandle.raw();
        if (!(sequenceRaw instanceof SequenceValue sequence)) {
            throw new IllegalArgumentException("First input must be SequenceValue");
        }
        List<Object> rawKeys = toCandidateValues(
                keyHandle, context.candidateCount());
        List<Object> normalizedKeys = rawKeys.stream()
                .map(provider::normalizeQueryKey)
                .toList();

        Set<Object> uniqueKeys = new LinkedHashSet<>(normalizedKeys);
        state.setDedupCounts(normalizedKeys.size(), uniqueKeys.size());

        SequenceIndexCacheKey indexCacheKey = new SequenceIndexCacheKey(0, keyDomain, sequence);
        IndexValue index;
        RuntimeCache.CacheLookup cachedIndex = context.runtimeCache().lookup(
                CacheKind.SEQUENCE_INDEX, indexCacheKey, state);
        if (cachedIndex.hit()) {
            if (!(cachedIndex.value() instanceof IndexValue cached)) {
                throw new IllegalStateException("Sequence index cache contains an incompatible value");
            }
            index = cached;
        } else {
            index = provider.build(sequence);
            context.runtimeCache().put(
                    CacheKind.SEQUENCE_INDEX, indexCacheKey, index, state);
        }

        Map<Object, Integer> countsByKey = new LinkedHashMap<>();
        for (Object key : uniqueKeys) {
            SequenceKeyCountCacheKey countCacheKey =
                    new SequenceKeyCountCacheKey(0, keyDomain, sequence, key);
            RuntimeCache.CacheLookup cachedCount = context.runtimeCache().lookup(
                    CacheKind.SEQUENCE_COUNT, countCacheKey, state);
            if (cachedCount.hit()) {
                countsByKey.put(key, (Integer) cachedCount.value());
            } else {
                int count = index.count(key);
                context.runtimeCache().put(
                        CacheKind.SEQUENCE_COUNT, countCacheKey, count, state);
                countsByKey.put(key, count);
            }
        }

        List<Object> result = new ArrayList<>(normalizedKeys.size());
        for (Object key : normalizedKeys) result.add(countsByKey.get(key));
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
        List<Object> result = new ArrayList<>(context.candidateCount());
        int totalUniqueKeys = 0;
        for (int groupIndex = 0; groupIndex < context.onlineGroupCount(); groupIndex++) {
            Object sequenceRaw = requestValue(sequenceHandle, groupIndex);
            if (!(sequenceRaw instanceof SequenceValue sequence)) {
                throw new IllegalArgumentException(
                        "First input must be SequenceValue for online batch group "
                                + groupIndex + " ("
                                + context.onlineGroupExecutionId(groupIndex) + ")");
            }
            int start = context.candidateGroupStart(groupIndex);
            int end = context.candidateGroupEnd(groupIndex);
            if (start == end) continue;
            List<Object> normalizedKeys = new ArrayList<>(end - start);
            for (int candidateIndex = start; candidateIndex < end; candidateIndex++) {
                try {
                    normalizedKeys.add(provider.normalizeQueryKey(
                            candidateValue(keyHandle, candidateIndex, groupIndex)));
                } catch (RuntimeException error) {
                    throw new IllegalArgumentException(
                            "Invalid key for online batch group " + groupIndex + " ("
                                    + context.onlineGroupExecutionId(groupIndex)
                                    + "), candidate "
                                    + context.candidateIndexInGroup(candidateIndex)
                                    + ": " + error.getMessage(),
                            error);
                }
            }
            Set<Object> uniqueKeys = new LinkedHashSet<>(normalizedKeys);
            totalUniqueKeys += uniqueKeys.size();

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
                index = provider.build(sequence);
                context.runtimeCache().put(
                        CacheKind.SEQUENCE_INDEX, indexCacheKey, index, state);
            }

            Map<Object, Integer> countsByKey = new LinkedHashMap<>();
            for (Object key : uniqueKeys) {
                SequenceKeyCountCacheKey countCacheKey = new SequenceKeyCountCacheKey(
                        groupIndex, keyDomain, sequence, key);
                RuntimeCache.CacheLookup cachedCount = context.runtimeCache().lookup(
                        CacheKind.SEQUENCE_COUNT, countCacheKey, state);
                if (cachedCount.hit()) {
                    countsByKey.put(key, (Integer) cachedCount.value());
                } else {
                    int count = index.count(key);
                    context.runtimeCache().put(
                            CacheKind.SEQUENCE_COUNT, countCacheKey, count, state);
                    countsByKey.put(key, count);
                }
            }
            for (Object key : normalizedKeys) result.add(countsByKey.get(key));
        }
        state.setDedupCounts(context.candidateCount(), totalUniqueKeys);
        return new CandidateBatchValue(result, node.logicalValueShape());
    }

    private static Object requestValue(ValueHandle handle, int groupIndex) {
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
        List<Object> result = new ArrayList<>(candidateCount);
        for (int index = 0; index < candidateCount; index++) result.add(handle.raw());
        return result;
    }
}
