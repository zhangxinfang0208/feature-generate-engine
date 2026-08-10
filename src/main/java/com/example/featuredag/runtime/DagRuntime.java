package com.example.featuredag.runtime;

import com.example.featuredag.logical.ValueShape;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.physical.ExecutorType;
import com.example.featuredag.physical.PhysicalNode;
import com.example.featuredag.physical.PhysicalPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Executes a physical plan without reparsing expressions. */
public final class DagRuntime {
    private final OperatorRegistry operatorRegistry;

    private record IndustryIndexCacheKey(SequenceValue sequence) {}

    private record CandidateCountCacheKey(
            String physicalNodeId,
            SequenceValue sequence,
            String industry) {}

    public DagRuntime(OperatorRegistry operatorRegistry) {
        this.operatorRegistry = Objects.requireNonNull(operatorRegistry, "operatorRegistry");
    }

    public ExecutionResult execute(PhysicalPlan plan, ExecutionContext context) {
        if (plan.environment() != context.environment()) {
            throw new IllegalArgumentException(
                    "Plan environment " + plan.environment() + " does not match context " + context.environment());
        }
        // 运行时：按物理计划顺序逐节点执行，各节点结果写入执行上下文的输出槽（slot:N）
        for (PhysicalNode node : plan.nodes()) {
            executeNode(node, context);
        }
        Map<String, ValueHandle> outputs = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : plan.outputFeatureSlots().entrySet()) {
            ValueHandle value = context.resultSlots().get(entry.getValue());
            if (value == null) {
                throw new IllegalStateException("Output slot not produced: " + entry.getValue());
            }
            outputs.put(entry.getKey(), value);
        }
        // 运行时：从输出槽收集根特征结果，连同各节点运行状态一起返回
        return new ExecutionResult(outputs, context.nodeStates());
    }

    private void executeNode(PhysicalNode node, ExecutionContext context) {
        RuntimeNodeState state = context.state(node.physicalNodeId());
        state.markRunning();
        long start = System.nanoTime();
        try {
            ValueHandle result = switch (node.executorType()) {
                case SOURCE_BINDING -> executeSource(node, context);
                case LITERAL -> wrap(
                        node.executorConfig().get("value"),
                        node.logicalValueShape(),
                        context.executionId());
                case FEATURE_OUTPUT -> requireSingleInput(node, context);
                case GENERIC_OPERATOR -> executeGenericOperator(node, context);
                case COUNT_INDUSTRY_BATCH -> executeCountIndustryBatch(node, context, state);
            };
            context.resultSlots().put(node.outputSlot(), result);
            state.markSuccess(result, System.nanoTime() - start);
        } catch (Throwable error) {
            state.markFailure(error, System.nanoTime() - start);
            throw error instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new IllegalStateException("Physical node failed: " + node.physicalNodeId(), error);
        }
    }

    /**
     * 源节点取值（运行时）：取值优先级为 候选向量（ONLINE + ITEM 域）→ 共享源值 → 默认值；
     * 候选源值缺失且无默认值时抛错，并定位到具体候选下标。
     */
    private ValueHandle executeSource(PhysicalNode node, ExecutionContext context) {
        String featureName = String.valueOf(node.executorConfig().get("sourceBinding"));
        Object defaultValue = node.executorConfig().get("defaultValue");
        @SuppressWarnings("unchecked")
        List<String> scopes = (List<String>) node.executorConfig().getOrDefault("entityScopes", List.of());
        boolean itemScoped = scopes.contains("ITEM");

        if (context.environment() == com.example.featuredag.physical.ExecutionEnvironment.ONLINE && itemScoped) {
            List<Object> values = new ArrayList<>(context.candidateCount());
            for (int index = 0; index < context.candidates().size(); index++) {
                Map<String, Object> candidate = context.candidates().get(index);
                if (candidate.containsKey(featureName)) {
                    values.add(candidate.get(featureName));
                } else if (defaultValue != null) {
                    values.add(defaultValue);
                } else {
                    throw new IllegalArgumentException(
                            "Missing source feature " + featureName + " for candidate " + index);
                }
            }
            return new CandidateVectorValue(values);
        }
        if (context.sharedSourceValues().containsKey(featureName)) {
            return wrapSource(
                    context.sharedSourceValues().get(featureName),
                    node.logicalValueShape(),
                    context);
        }
        if (defaultValue == null) {
            throw new IllegalArgumentException("Missing source feature: " + featureName);
        }
        return wrapSource(defaultValue, node.logicalValueShape(), context);
    }

    /** 通用算子执行（运行时）：从输入槽取出已算好的值句柄，交给算子注册表求值。 */
    private ValueHandle executeGenericOperator(PhysicalNode node, ExecutionContext context) {
        String operatorName = String.valueOf(node.executorConfig().get("operatorName"));
        List<ValueHandle> inputHandles = node.inputSlots().stream()
                .map(slot -> requireSlot(context, slot))
                .toList();
        return vectorizedApply(operatorName, inputHandles, context, node.logicalValueShape());
    }

    /**
     * 融合算子执行（countIndustry，C9 融合产物）：
     * ① 对候选行业去重统计；② 序列行业索引按序列句柄缓存（REQUEST_INDEX），
     * ③ 每个行业计数按「物理节点 + 序列 + 行业」缓存（CANDIDATE_KEY）；
     * 最后把每个候选映射到对应行业计数，返回候选向量。
     */
    private ValueHandle executeCountIndustryBatch(
            PhysicalNode node,
            ExecutionContext context,
            RuntimeNodeState state) {
        if (node.inputSlots().size() != 2) {
            throw new IllegalStateException("COUNT_INDUSTRY_BATCH requires sequence and industry inputs");
        }
        Object sequenceRaw = requireSlot(context, node.inputSlots().get(0)).raw();
        if (!(sequenceRaw instanceof SequenceValue sequence)) {
            throw new IllegalArgumentException("First input must be SequenceValue");
        }
        ValueHandle industriesHandle = requireSlot(context, node.inputSlots().get(1));
        List<Object> industries = toCandidateValues(industriesHandle, context.candidateCount());

        Set<String> uniqueIndustries = new LinkedHashSet<>();
        for (Object industry : industries) uniqueIndustries.add(String.valueOf(industry));
        state.setDedupCounts(industries.size(), uniqueIndustries.size());

        Object indexKey = new IndustryIndexCacheKey(sequence);
        IndexValue index;
        Object cachedIndex = context.cacheRegistry().get(indexKey);
        if (cachedIndex instanceof IndexValue cached) {
            index = cached;
            state.markCacheHit("REQUEST_INDEX");
        } else {
            index = SequenceIndustryIndex.build(sequence);
            context.cacheRegistry().put(indexKey, index);
        }

        Map<String, Integer> countsByIndustry = new LinkedHashMap<>();
        for (String industry : uniqueIndustries) {
            Object cacheKey = new CandidateCountCacheKey(
                    node.physicalNodeId(), sequence, industry);
            Object cached = context.cacheRegistry().get(cacheKey);
            if (cached instanceof Integer value) {
                countsByIndustry.put(industry, value);
                state.markCacheHit("CANDIDATE_KEY");
            } else {
                int value = index.count(industry);
                context.cacheRegistry().put(cacheKey, value);
                countsByIndustry.put(industry, value);
            }
        }

        List<Object> result = new ArrayList<>(industries.size());
        for (Object industry : industries) result.add(countsByIndustry.get(String.valueOf(industry)));
        return new CandidateVectorValue(result);
    }

    /**
     * 向量化求值（运行时）：任一输入为候选向量即按候选下标逐元素求值，
     * 标量输入在所有候选间共享；无候选向量时退化为单次求值。
     */
    private ValueHandle vectorizedApply(
            String operatorName,
            List<ValueHandle> inputHandles,
            ExecutionContext context,
            ValueShape logicalValueShape) {
        boolean vector = inputHandles.stream().anyMatch(handle -> handle instanceof CandidateVectorValue);
        if (!vector) {
            List<Object> args = inputHandles.stream().map(ValueHandle::raw).toList();
            return wrap(operatorRegistry.evaluate(operatorName, args), logicalValueShape, context.executionId());
        }
        int size = context.candidateCount();
        if (size <= 0) {
            size = inputHandles.stream()
                    .filter(CandidateVectorValue.class::isInstance)
                    .map(CandidateVectorValue.class::cast)
                    .mapToInt(CandidateVectorValue::size)
                    .max()
                    .orElseThrow();
        }
        List<Object> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            List<Object> args = new ArrayList<>(inputHandles.size());
            for (ValueHandle handle : inputHandles) {
                if (handle instanceof CandidateVectorValue vectorValue) {
                    args.add(vectorValue.valueAt(index));
                } else {
                    args.add(handle.raw());
                }
            }
            result.add(operatorRegistry.evaluate(operatorName, args));
        }
        return new CandidateVectorValue(result);
    }

    private static ValueHandle requireSingleInput(PhysicalNode node, ExecutionContext context) {
        if (node.inputSlots().size() != 1) {
            throw new IllegalStateException("Feature output must have exactly one input");
        }
        return requireSlot(context, node.inputSlots().get(0));
    }

    private static ValueHandle requireSlot(ExecutionContext context, String slot) {
        ValueHandle value = context.resultSlots().get(slot);
        if (value == null) throw new IllegalStateException("Input slot not available: " + slot);
        return value;
    }

    private static List<Object> toCandidateValues(ValueHandle handle, int candidateCount) {
        if (handle instanceof CandidateVectorValue vector) return vector.values();
        List<Object> result = new ArrayList<>(candidateCount);
        for (int i = 0; i < candidateCount; i++) result.add(handle.raw());
        return result;
    }

    private static ValueHandle wrapSource(
            Object value,
            ValueShape logicalValueShape,
            ExecutionContext context) {
        if (value instanceof ValueHandle handle) return handle;
        if (logicalValueShape == ValueShape.SEQUENCE && value instanceof List<?> list) {
            return new ListSequenceValue(context.executionId(), list);
        }
        return wrap(value, logicalValueShape, context.executionId());
    }

    /**
     * 值包装（运行时）：按逻辑值形状把普通对象包成对应句柄——
     * SEQUENCE 形状的 List → ListSequenceValue，CANDIDATE_VECTOR 形状的 List → 候选向量，
     * 其余 → ScalarValue；已是句柄则原样返回。
     */
    private static ValueHandle wrap(
            Object value,
            ValueShape logicalValueShape,
            String alignmentId) {
        if (value instanceof ValueHandle handle) return handle;
        if (logicalValueShape == ValueShape.SEQUENCE && value instanceof List<?> list) {
            return new ListSequenceValue(alignmentId, list);
        }
        if (logicalValueShape == ValueShape.CANDIDATE_VECTOR && value instanceof List<?> list) {
            return new CandidateVectorValue(new ArrayList<>(list));
        }
        return new ScalarValue(value);
    }
}
