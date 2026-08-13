package com.example.featuredag.runtime;

import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.BatchColumn;
import com.example.featuredag.operator.BatchDomain;
import com.example.featuredag.operator.BatchKernelKind;
import com.example.featuredag.operator.BatchLayout;
import com.example.featuredag.operator.BatchOperatorCall;
import com.example.featuredag.operator.BatchOperatorEvaluationException;
import com.example.featuredag.operator.BatchOperatorResult;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.physical.ExecutorType;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.physical.ExecutionStage;
import com.example.featuredag.physical.OperatorInvocationPolicy;
import com.example.featuredag.physical.PhysicalNode;
import com.example.featuredag.physical.PhysicalPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 运行时只消费已确定的物理计划：按拓扑序读取输入槽并写入唯一输出槽（C9），
 * 执行阶段、模式、缓存策略及专用执行器均由物理层给定，运行时不再临时决策（C10）。
 */
public final class DagRuntime {
    private final OperatorRegistry operatorRegistry;
    private final PhysicalExecutorRegistry executorRegistry;

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
        validateExecutionStages(plan, context);
        // C9：按物理拓扑序逐节点执行，每个节点只写入计划分配的唯一输出槽（slot:N）。
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
        // C9：输出特征只从物理计划声明的一一对应根槽位收集。
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
                case SPECIALIZED -> {
                    state.recordOperatorInvocation(
                            OperatorInvocationKind.SPECIALIZED, null, 0);
                    yield executorRegistry.require(node.executorId()).execute(node, context, state);
                }
            };
            context.resultSlots().put(node.outputSlot(), result);
            state.markSuccess(result, System.nanoTime() - start);
        } catch (RuntimeException error) {
            state.markFailure(error, System.nanoTime() - start);
            throw error;
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
        return switch (invocationPolicy(node)) {
            case SINGLE_OR_BATCH_BY_INPUT_DOMAIN -> applySingleOrBatchByInputDomain(
                    node,
                    operatorName,
                    inputHandles,
                    context,
                    state,
                    logicalValueShape);
        };
    }

    private ValueHandle applySingleOrBatchByInputDomain(
            PhysicalNode node,
            String operatorName,
            List<ValueHandle> inputHandles,
            ExecutionContext context,
            RuntimeNodeState state,
            ValueShape logicalValueShape) {
        EvaluationDomain domain = evaluationDomain(inputHandles);
        if (domain == EvaluationDomain.NONE) {
            state.recordOperatorInvocation(OperatorInvocationKind.SINGLE, null, 0);
            List<Object> args = inputHandles.stream().map(ValueHandle::raw).toList();
            String singleKernelId = String.valueOf(node.executorConfig().getOrDefault(
                    "singleKernelId", operatorName));
            return wrap(
                    operatorRegistry.evaluate(singleKernelId, args),
                    logicalValueShape,
                    context.executionId());
        }
        // C10：批维度来自执行上下文，标量/字面量在批内广播，不在运行时改变执行阶段。
        int size = evaluationSize(domain, context);
        for (ValueHandle handle : inputHandles) {
            validateBatchValueSize(handle, domain, size, context, operatorName);
        }
        BatchKernelKind plannedKind = plannedBatchKernelKind(node);
        state.recordOperatorInvocation(
                plannedKind == BatchKernelKind.NATIVE
                        ? OperatorInvocationKind.BATCH_NATIVE
                        : OperatorInvocationKind.BATCH_SCALAR_ADAPTER,
                batchDomain(domain),
                0);
        List<Object> result = evaluateBatch(
                node, operatorName, inputHandles, domain, context, state);
        return switch (domain) {
            case SINGLE_CANDIDATE -> new CandidateVectorValue(result);
            case OFFLINE_ROW -> new OfflineBatchValue(result, logicalValueShape);
            case ONLINE_REQUEST -> new RequestBatchValue(result, logicalValueShape);
            case ONLINE_CANDIDATE -> new CandidateBatchValue(result, logicalValueShape);
            case NONE -> throw new IllegalStateException("Missing evaluation domain");
        };
    }

    private static OperatorInvocationPolicy invocationPolicy(PhysicalNode node) {
        Object value = node.executorConfig().getOrDefault(
                "invocationPolicy",
                OperatorInvocationPolicy.SINGLE_OR_BATCH_BY_INPUT_DOMAIN);
        if (value instanceof OperatorInvocationPolicy policy) return policy;
        throw new IllegalStateException(
                "Invalid operator invocation policy for " + node.physicalNodeId() + ": " + value);
    }

    private static BatchKernelKind plannedBatchKernelKind(PhysicalNode node) {
        return BatchKernelKind.valueOf(String.valueOf(
                node.executorConfig().getOrDefault(
                        "batchKernelKind", BatchKernelKind.SCALAR_ADAPTER.name())));
    }

    private static BatchDomain batchDomain(EvaluationDomain domain) {
        return switch (domain) {
            case SINGLE_CANDIDATE, ONLINE_CANDIDATE -> BatchDomain.ONLINE_CANDIDATE;
            case OFFLINE_ROW -> BatchDomain.OFFLINE_ROW;
            case ONLINE_REQUEST -> BatchDomain.ONLINE_REQUEST;
            case NONE -> throw new IllegalArgumentException("Scalar evaluation has no Batch domain");
        };
    }

    /**
     * 通用 Batch 执行（C10）：Single/Batch Kernel ID 已固化在物理节点配置中；
     * 运行时只依据输入值句柄形成批域和虚拟广播列，不做节点融合或算法改写。
     */
    private List<Object> evaluateBatch(
            PhysicalNode node,
            String operatorName,
            List<ValueHandle> inputHandles,
            EvaluationDomain domain,
            ExecutionContext context,
            RuntimeNodeState state) {
        RuntimeBatchLayout layout = new RuntimeBatchLayout(domain, context);
        state.setBatchRowCount(layout.rowCount());
        List<BatchColumn> arguments = inputHandles.stream()
                .map(handle -> (BatchColumn) new InputBatchColumn(handle, layout, context))
                .toList();
        BatchOperatorResult result;
        try {
            BatchKernelKind plannedKind = plannedBatchKernelKind(node);
            String batchKernelId = String.valueOf(node.executorConfig().getOrDefault(
                    "batchKernelId", operatorName));
            result = operatorRegistry.evaluateBatch(
                    batchKernelId, new BatchOperatorCall(layout, arguments), plannedKind);
        } catch (BatchOperatorEvaluationException error) {
            int originalRow = layout.originalRowIndex(error.rowIndex());
            throw batchRowFailure(
                    operatorName, domain, originalRow, context, error.getCause());
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(
                    "Operator " + operatorName + " failed for "
                            + batchLocation(domain) + ": " + error.getMessage(),
                    error);
        }
        List<Object> values = new ArrayList<>(result.values().size());
        for (int rowIndex = 0; rowIndex < result.values().size(); rowIndex++) {
            values.add(result.values().valueAt(rowIndex));
        }
        return values;
    }

    private static IllegalArgumentException batchRowFailure(
            String operatorName,
            EvaluationDomain domain,
            int rowIndex,
            ExecutionContext context,
            Throwable cause) {
        return new IllegalArgumentException(
                "Operator " + operatorName + " failed for "
                        + evaluationLocation(domain, rowIndex, context)
                        + ": " + cause.getMessage(),
                cause);
    }

    private static String batchLocation(EvaluationDomain domain) {
        return switch (domain) {
            case SINGLE_CANDIDATE, ONLINE_CANDIDATE -> "candidate batch";
            case OFFLINE_ROW -> "offline batch";
            case ONLINE_REQUEST -> "online request batch";
            case NONE -> "scalar value";
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
        // ONLINE 允许零候选：候选域节点执行零次并产出空向量，供公共 API 原样编码。
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

    private static final class RuntimeBatchLayout implements BatchLayout {
        private final EvaluationDomain evaluationDomain;
        private final ExecutionContext context;
        private final int rowCount;

        private RuntimeBatchLayout(
                EvaluationDomain evaluationDomain,
                ExecutionContext context) {
            if (evaluationDomain == EvaluationDomain.NONE) {
                throw new IllegalArgumentException("Scalar evaluation does not have a Batch layout");
            }
            this.evaluationDomain = evaluationDomain;
            this.context = context;
            this.rowCount = evaluationSize(evaluationDomain, context);
        }

        @Override
        public BatchDomain domain() {
            return batchDomain(evaluationDomain);
        }

        @Override
        public int rowCount() {
            return rowCount;
        }

        @Override
        public int groupIndexAt(int rowIndex) {
            return evaluationGroupIndex(
                    evaluationDomain, originalRowIndex(rowIndex), context);
        }

        @Override
        public int indexInGroupAt(int rowIndex) {
            int originalRow = originalRowIndex(rowIndex);
            return switch (evaluationDomain) {
                case SINGLE_CANDIDATE, OFFLINE_ROW -> originalRow;
                case ONLINE_CANDIDATE -> context.candidateIndexInGroup(originalRow);
                case ONLINE_REQUEST -> 0;
                case NONE -> throw new IllegalStateException("Scalar evaluation has no Batch row");
            };
        }

        private int originalRowIndex(int rowIndex) {
            if (rowIndex < 0 || rowIndex >= rowCount) {
                throw new IndexOutOfBoundsException(
                        "Batch row " + rowIndex + " out of bounds for size " + rowCount);
            }
            return rowIndex;
        }
    }

    private static final class InputBatchColumn implements BatchColumn {
        private final ValueHandle handle;
        private final RuntimeBatchLayout layout;
        private final ExecutionContext context;

        private InputBatchColumn(
                ValueHandle handle,
                RuntimeBatchLayout layout,
                ExecutionContext context) {
            this.handle = handle;
            this.layout = layout;
            this.context = context;
        }

        @Override
        public int size() {
            return layout.rowCount();
        }

        @Override
        public Object valueAt(int rowIndex) {
            return argumentAt(
                    handle,
                    layout.evaluationDomain,
                    layout.originalRowIndex(rowIndex),
                    context);
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

    /**
     * C10 最小闭环：计划固化阶段与执行上下文一致性校验——
     * OFFLINE 计划只允许 OFFLINE_BATCH 阶段节点，ONLINE 计划只允许候选批/请求共享节点。
     */
    private static void validateExecutionStages(PhysicalPlan plan, ExecutionContext context) {
        for (PhysicalNode node : plan.nodes()) {
            if (context.environment() == ExecutionEnvironment.OFFLINE) {
                if (node.executionStage() != ExecutionStage.OFFLINE_BATCH) {
                    throw new IllegalArgumentException(
                            "Physical node " + node.physicalNodeId() + " has stage "
                                    + node.executionStage() + " but plan environment is OFFLINE");
                }
            } else if (node.executionStage() != ExecutionStage.CANDIDATE_BATCH
                    && node.executionStage() != ExecutionStage.REQUEST_SHARED) {
                throw new IllegalArgumentException(
                        "Physical node " + node.physicalNodeId() + " has stage "
                                + node.executionStage() + " but plan environment is ONLINE");
            }
        }
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
