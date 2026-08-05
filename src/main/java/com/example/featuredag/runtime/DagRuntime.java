package com.example.featuredag.runtime;

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

    public DagRuntime(OperatorRegistry operatorRegistry) {
        this.operatorRegistry = Objects.requireNonNull(operatorRegistry, "operatorRegistry");
    }

    public ExecutionResult execute(PhysicalPlan plan, ExecutionContext context) {
        if (plan.environment() != context.environment()) {
            throw new IllegalArgumentException(
                    "Plan environment " + plan.environment() + " does not match context " + context.environment());
        }
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
        return new ExecutionResult(outputs, context.nodeStates());
    }

    private void executeNode(PhysicalNode node, ExecutionContext context) {
        RuntimeNodeState state = context.state(node.physicalNodeId());
        state.markRunning();
        long start = System.nanoTime();
        try {
            ValueHandle result = switch (node.executorType()) {
                case SOURCE_BINDING -> executeSource(node, context);
                case LITERAL -> wrap(node.executorConfig().get("value"));
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
            return wrap(context.sharedSourceValues().get(featureName));
        }
        if (defaultValue == null) {
            throw new IllegalArgumentException("Missing source feature: " + featureName);
        }
        return wrap(defaultValue);
    }

    private ValueHandle executeGenericOperator(PhysicalNode node, ExecutionContext context) {
        String operatorName = String.valueOf(node.executorConfig().get("operatorName"));
        List<ValueHandle> inputHandles = node.inputSlots().stream()
                .map(slot -> requireSlot(context, slot))
                .toList();
        return vectorizedApply(operatorName, inputHandles, context.candidateCount());
    }

    private ValueHandle executeCountIndustryBatch(
            PhysicalNode node,
            ExecutionContext context,
            RuntimeNodeState state) {
        if (node.inputSlots().size() != 2) {
            throw new IllegalStateException("COUNT_INDUSTRY_BATCH requires sequence and industry inputs");
        }
        Object sequenceRaw = requireSlot(context, node.inputSlots().get(0)).raw();
        if (!(sequenceRaw instanceof SequenceValue sequenceValue)) {
            throw new IllegalArgumentException("First input must be SequenceValue");
        }
        SequenceBlock base = sequenceValue.baseBlock();
        ValueHandle industriesHandle = requireSlot(context, node.inputSlots().get(1));
        List<Object> industries = toCandidateValues(industriesHandle, context.candidateCount());

        Set<String> uniqueIndustries = new LinkedHashSet<>();
        for (Object industry : industries) uniqueIndustries.add(String.valueOf(industry));
        state.setDedupCounts(industries.size(), uniqueIndustries.size());

        String indexKey = "industryIndex|" + base.handleKey();
        IndexValue index;
        Object cachedIndex = context.cacheRegistry().get(indexKey);
        if (cachedIndex instanceof IndexValue cached) {
            index = cached;
            state.markCacheHit("REQUEST_INDEX");
        } else {
            index = SequenceIndustryIndex.build(base);
            context.cacheRegistry().put(indexKey, index);
        }

        Map<String, Integer> countsByIndustry = new LinkedHashMap<>();
        for (String industry : uniqueIndustries) {
            String cacheKey = node.physicalNodeId() + "|" + base.handleKey() + "|" + industry;
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

    private ValueHandle vectorizedApply(
            String operatorName,
            List<ValueHandle> inputHandles,
            int candidateCount) {
        boolean vector = inputHandles.stream().anyMatch(handle -> handle instanceof CandidateVectorValue);
        if (!vector) {
            List<Object> args = inputHandles.stream().map(ValueHandle::raw).toList();
            return wrap(operatorRegistry.evaluate(operatorName, args));
        }
        int size = candidateCount;
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

    private static ValueHandle wrap(Object value) {
        if (value instanceof ValueHandle handle) return handle;
        if (value instanceof List<?> list) return new CandidateVectorValue(new ArrayList<>(list));
        return new ScalarValue(value);
    }
}
