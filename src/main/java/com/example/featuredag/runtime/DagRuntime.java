package com.example.featuredag.runtime;

import com.example.featuredag.logical.ValueShape;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.physical.CachePolicy;
import com.example.featuredag.physical.ExecutorType;
import com.example.featuredag.physical.PhysicalNode;
import com.example.featuredag.physical.PhysicalPlan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Executes a physical plan without reparsing expressions. */
public final class DagRuntime {
    private final OperatorRegistry operatorRegistry;
    private final PhysicalExecutorRegistry executorRegistry;

    private record OperatorInvocationCacheKey(
            String physicalNodeId,
            int groupIndex,
            List<Object> arguments) {
        private OperatorInvocationCacheKey {
            arguments = Collections.unmodifiableList(new ArrayList<>(arguments));
        }
    }

    public DagRuntime(OperatorRegistry operatorRegistry) {
        this(
                operatorRegistry,
                PhysicalExecutorRegistry.standard(SequenceIndexRegistry.standard()));
    }

    public DagRuntime(
            OperatorRegistry operatorRegistry,
            PhysicalExecutorRegistry executorRegistry) {
        this.operatorRegistry = Objects.requireNonNull(operatorRegistry, "operatorRegistry");
        this.executorRegistry = Objects.requireNonNull(executorRegistry, "executorRegistry");
    }

    public ExecutionResult execute(PhysicalPlan plan, ExecutionContext context) {
        if (plan.environment() != context.environment()) {
            throw new IllegalArgumentException(
                    "Plan environment " + plan.environment() + " does not match context " + context.environment());
        }
        executorRegistry.validate(plan);
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
        return new ExecutionResult(
                outputs, context.nodeStates(), context.runtimeCache().snapshot());
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
                case GENERIC_OPERATOR -> executeGenericOperator(node, context, state);
                case SPECIALIZED -> executorRegistry.require(node.executorId()).execute(node, context, state);
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

        if (context.isOfflineBatch()) {
            // C10：OFFLINE_BATCH 已由物理计划确定；运行时只按批内行号绑定 RAW 值。
            List<Object> values = new ArrayList<>(context.offlineBatchSize());
            for (int index = 0; index < context.offlineRows().size(); index++) {
                Map<String, Object> row = context.offlineRows().get(index);
                if (row.containsKey(featureName)) {
                    values.add(row.get(featureName));
                } else if (defaultValue != null) {
                    values.add(defaultValue);
                } else {
                    throw new IllegalArgumentException(
                            "Missing source feature " + featureName
                                    + " for offline batch row " + index);
                }
            }
            return new OfflineBatchValue(values, node.logicalValueShape());
        }

        if (context.isOnlineBatch()) {
            if (itemScoped) {
                List<Object> values = new ArrayList<>(context.candidateCount());
                for (int candidateIndex = 0;
                        candidateIndex < context.candidates().size();
                        candidateIndex++) {
                    Map<String, Object> candidate = context.candidates().get(candidateIndex);
                    if (candidate.containsKey(featureName)) {
                        values.add(candidate.get(featureName));
                    } else if (defaultValue != null) {
                        values.add(defaultValue);
                    } else {
                        int groupIndex = context.candidateGroupIndex(candidateIndex);
                        throw new IllegalArgumentException(
                                "Missing source feature " + featureName
                                        + " for online batch group " + groupIndex
                                        + " (" + context.onlineGroupExecutionId(groupIndex) + ")"
                                        + ", candidate "
                                        + context.candidateIndexInGroup(candidateIndex));
                    }
                }
                return new CandidateBatchValue(values, node.logicalValueShape());
            }
            List<Object> values = new ArrayList<>(context.onlineGroupCount());
            for (int groupIndex = 0;
                    groupIndex < context.onlineSharedGroups().size();
                    groupIndex++) {
                Map<String, Object> group = context.onlineSharedGroups().get(groupIndex);
                if (group.containsKey(featureName)) {
                    values.add(group.get(featureName));
                } else if (defaultValue != null) {
                    values.add(defaultValue);
                } else {
                    throw new IllegalArgumentException(
                            "Missing source feature " + featureName
                                    + " for online batch group " + groupIndex
                                    + " (" + context.onlineGroupExecutionId(groupIndex) + ")");
                }
            }
            return new RequestBatchValue(values, node.logicalValueShape());
        }

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
    private ValueHandle executeGenericOperator(
            PhysicalNode node,
            ExecutionContext context,
            RuntimeNodeState state) {
        String operatorName = String.valueOf(node.executorConfig().get("operatorName"));
        List<ValueHandle> inputHandles = node.inputSlots().stream()
                .map(slot -> requireSlot(context, slot))
                .toList();
        return vectorizedApply(
                node, operatorName, inputHandles, context, state, node.logicalValueShape());
    }

    /**
     * 向量化求值（运行时）：根据值句柄确定单候选、离线行、在线请求组或在线候选域；
     * 在线候选域通过 groupOffsets 广播所属请求组的共享值，普通标量在整个求值域广播。
     */
    private ValueHandle vectorizedApply(
            PhysicalNode node,
            String operatorName,
            List<ValueHandle> inputHandles,
            ExecutionContext context,
            RuntimeNodeState state,
            ValueShape logicalValueShape) {
        EvaluationDomain domain = evaluationDomain(inputHandles);
        if (domain == EvaluationDomain.NONE) {
            List<Object> args = inputHandles.stream().map(ValueHandle::raw).toList();
            return wrap(operatorRegistry.evaluate(operatorName, args), logicalValueShape, context.executionId());
        }
        // C10：批维度来自执行上下文，标量/字面量在批内广播，不在运行时改变执行阶段。
        int size = evaluationSize(domain, context);
        for (ValueHandle handle : inputHandles) {
            validateBatchValueSize(handle, domain, size, context, operatorName);
        }
        List<Object> result = new ArrayList<>(size);
        boolean memoize = node.cachePolicy() == CachePolicy.CANDIDATE_KEY;
        if (memoize) {
            var definition = operatorRegistry.require(operatorName);
            if (!definition.deterministic() || !definition.sideEffectFree()) {
                throw new IllegalStateException(
                        "CANDIDATE_KEY cache requires a deterministic side-effect-free operator: "
                                + operatorName);
            }
        }
        Set<OperatorInvocationCacheKey> uniqueInvocations = new LinkedHashSet<>();
        for (int index = 0; index < size; index++) {
            List<Object> args = new ArrayList<>(inputHandles.size());
            for (ValueHandle handle : inputHandles) {
                args.add(argumentAt(handle, domain, index, context));
            }
            try {
                Object value;
                if (!memoize) {
                    value = operatorRegistry.evaluate(operatorName, args);
                } else {
                    int groupIndex = evaluationGroupIndex(domain, index, context);
                    OperatorInvocationCacheKey cacheKey = new OperatorInvocationCacheKey(
                            node.physicalNodeId(), groupIndex, args);
                    uniqueInvocations.add(cacheKey);
                    RuntimeCache.CacheLookup cached = context.runtimeCache().lookup(
                            CacheKind.CANDIDATE_KEY, cacheKey, state);
                    if (cached.hit()) {
                        value = cached.value();
                    } else {
                        value = operatorRegistry.evaluate(operatorName, args);
                        context.runtimeCache().put(
                                CacheKind.CANDIDATE_KEY, cacheKey, value, state);
                    }
                }
                result.add(value);
            } catch (RuntimeException error) {
                throw new IllegalArgumentException(
                        "Operator " + operatorName + " failed for "
                                + evaluationLocation(domain, index, context)
                                + ": " + error.getMessage(),
                        error);
            }
        }
        if (memoize) state.setDedupCounts(size, uniqueInvocations.size());
        return switch (domain) {
            case SINGLE_CANDIDATE -> new CandidateVectorValue(result);
            case OFFLINE_ROW -> new OfflineBatchValue(result, logicalValueShape);
            case ONLINE_REQUEST -> new RequestBatchValue(result, logicalValueShape);
            case ONLINE_CANDIDATE -> new CandidateBatchValue(result, logicalValueShape);
            case NONE -> throw new IllegalStateException("Missing evaluation domain");
        };
    }

    private static EvaluationDomain evaluationDomain(List<ValueHandle> handles) {
        boolean singleCandidate = handles.stream().anyMatch(CandidateVectorValue.class::isInstance);
        boolean offlineRow = handles.stream().anyMatch(OfflineBatchValue.class::isInstance);
        boolean onlineRequest = handles.stream().anyMatch(RequestBatchValue.class::isInstance);
        boolean onlineCandidate = handles.stream().anyMatch(CandidateBatchValue.class::isInstance);
        int incompatibleDomains = (singleCandidate ? 1 : 0)
                + (offlineRow ? 1 : 0)
                + ((onlineRequest || onlineCandidate) ? 1 : 0);
        if (incompatibleDomains > 1) {
            throw new IllegalStateException("Cannot mix values from different batch domains");
        }
        if (onlineCandidate) return EvaluationDomain.ONLINE_CANDIDATE;
        if (onlineRequest) return EvaluationDomain.ONLINE_REQUEST;
        if (offlineRow) return EvaluationDomain.OFFLINE_ROW;
        if (singleCandidate) return EvaluationDomain.SINGLE_CANDIDATE;
        return EvaluationDomain.NONE;
    }

    private static int evaluationSize(EvaluationDomain domain, ExecutionContext context) {
        return switch (domain) {
            case SINGLE_CANDIDATE, ONLINE_CANDIDATE -> context.candidateCount();
            case OFFLINE_ROW -> context.offlineBatchSize();
            case ONLINE_REQUEST -> context.onlineGroupCount();
            case NONE -> 1;
        };
    }

    private static void validateBatchValueSize(
            ValueHandle handle,
            EvaluationDomain domain,
            int evaluationSize,
            ExecutionContext context,
            String operatorName) {
        if (handle instanceof CandidateVectorValue value) {
            requireVectorSize(value.size(), evaluationSize, operatorName);
        } else if (handle instanceof OfflineBatchValue value) {
            requireVectorSize(value.size(), evaluationSize, operatorName);
        } else if (handle instanceof RequestBatchValue value) {
            requireVectorSize(value.size(), context.onlineGroupCount(), operatorName);
        } else if (handle instanceof CandidateBatchValue value) {
            if (domain != EvaluationDomain.ONLINE_CANDIDATE) {
                throw new IllegalStateException(
                        "Candidate batch value requires online candidate evaluation");
            }
            requireVectorSize(value.size(), evaluationSize, operatorName);
        }
    }

    private static Object argumentAt(
            ValueHandle handle,
            EvaluationDomain domain,
            int index,
            ExecutionContext context) {
        if (handle instanceof CandidateVectorValue value) return value.valueAt(index);
        if (handle instanceof OfflineBatchValue value) return value.valueAt(index);
        if (handle instanceof CandidateBatchValue value) return value.valueAt(index);
        if (handle instanceof RequestBatchValue value) {
            int groupIndex = domain == EvaluationDomain.ONLINE_CANDIDATE
                    ? context.candidateGroupIndex(index)
                    : index;
            return value.valueAt(groupIndex);
        }
        return handle.raw();
    }

    private static int evaluationGroupIndex(
            EvaluationDomain domain,
            int index,
            ExecutionContext context) {
        return switch (domain) {
            case ONLINE_CANDIDATE -> context.candidateGroupIndex(index);
            case ONLINE_REQUEST -> index;
            case SINGLE_CANDIDATE -> 0;
            case OFFLINE_ROW, NONE -> -1;
        };
    }

    private static String evaluationLocation(
            EvaluationDomain domain,
            int index,
            ExecutionContext context) {
        return switch (domain) {
            case OFFLINE_ROW -> "offline batch row " + index;
            case SINGLE_CANDIDATE -> "candidate " + index;
            case ONLINE_REQUEST -> "online batch group " + index + " ("
                    + context.onlineGroupExecutionId(index) + ")";
            case ONLINE_CANDIDATE -> {
                int groupIndex = context.candidateGroupIndex(index);
                yield "online batch group " + groupIndex + " ("
                        + context.onlineGroupExecutionId(groupIndex) + "), candidate "
                        + context.candidateIndexInGroup(index);
            }
            case NONE -> "scalar value";
        };
    }

    private static void requireVectorSize(int actual, int expected, String operatorName) {
        if (actual != expected) {
            throw new IllegalStateException(
                    "Operator " + operatorName + " received vector size " + actual
                            + ", expected " + expected);
        }
    }

    private enum EvaluationDomain {
        NONE,
        SINGLE_CANDIDATE,
        OFFLINE_ROW,
        ONLINE_REQUEST,
        ONLINE_CANDIDATE
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
