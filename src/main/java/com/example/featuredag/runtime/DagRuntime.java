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
import com.example.featuredag.operator.OperatorSequence;
import com.example.featuredag.physical.ExecutorType;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.physical.ExecutionStage;
import com.example.featuredag.physical.OperatorInvocationPolicy;
import com.example.featuredag.physical.PhysicalNode;
import com.example.featuredag.physical.PhysicalPlan;
import com.example.featuredag.physical.SequenceViewInputMode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
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
        // 状态更新与槽写入包在同一异常边界内：成功节点一定有结果，失败节点保留耗时和原始异常。
        state.markRunning();
        long start = System.nanoTime();
        try {
            ValueHandle result = switch (node.executorType()) {
                case SOURCE_BINDING -> executeSource(node, context);
                case LITERAL -> wrap(
                        node.executorConfig().get("value"),
                        node.logicalValueShape(),
                        context.executionId());
                case FEATURE_OUTPUT -> executeFeatureOutput(node, context, state);
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
            return OfflineBatchValue.owned(values, node.logicalValueShape());
        }

        if (context.isOnlineBatch()) {
            if (itemScoped) {
                // ITEM 域按展平后的候选顺序绑定，结果句柄长度必须等于全批候选数。
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
                return CandidateBatchValue.owned(values, node.logicalValueShape());
            }
            // USER/SCENE 等非 ITEM 源值每组一份，形成 RequestBatchValue，后续可广播到组内候选。
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
            return RequestBatchValue.owned(values, node.logicalValueShape());
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
            SequenceViewInputMode sequenceMode = sequenceViewInputMode(node);
            IdentityHashMap<OperatorSequence, Object> materialized = new IdentityHashMap<>();
            List<Object> args = new ArrayList<>(inputHandles.size());
            for (ValueHandle inputHandle : inputHandles) {
                args.add(SequenceViewArgumentAdapter.adapt(
                        inputHandle.raw(), sequenceMode, materialized));
            }
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
        // result 是本次求值刚构建的独占列表，不再被其他引用持有，交给对应 owned() 工厂避免二次拷贝。
        return switch (domain) {
            case SINGLE_CANDIDATE -> new CandidateVectorValue(result);
            case OFFLINE_ROW -> OfflineBatchValue.owned(result, logicalValueShape);
            case ONLINE_REQUEST -> RequestBatchValue.owned(result, logicalValueShape);
            case ONLINE_CANDIDATE -> CandidateBatchValue.owned(result, logicalValueShape);
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

    private static SequenceViewInputMode sequenceViewInputMode(PhysicalNode node) {
        Object value = node.executorConfig().getOrDefault(
                "sequenceViewInputMode", SequenceViewInputMode.MATERIALIZE);
        if (value instanceof SequenceViewInputMode mode) return mode;
        try {
            return SequenceViewInputMode.valueOf(String.valueOf(value));
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(
                    "Invalid sequence view input mode for " + node.physicalNodeId()
                            + ": " + value,
                    error);
        }
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
        SequenceViewInputMode sequenceMode = sequenceViewInputMode(node);
        // 不支持视图的算子按组复用物化结果；支持视图的算子则直接读取零拷贝序列协议。
        Map<Integer, IdentityHashMap<OperatorSequence, Object>> materializedByGroup =
                new LinkedHashMap<>();
        List<BatchColumn> arguments = inputHandles.stream()
                .map(handle -> (BatchColumn) new SequenceAdaptingBatchColumn(
                        new InputBatchColumn(handle, layout, context),
                        layout,
                        sequenceMode,
                        materializedByGroup))
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
        // 批域由输入句柄携带；在线请求值可向候选域广播，但离线行、在线候选等域禁止混用（C10）。
        boolean singleCandidate = false;
        boolean offlineRow = false;
        boolean onlineRequest = false;
        boolean onlineCandidate = false;
        for (ValueHandle handle : handles) {
            if (handle instanceof CandidateVectorValue) singleCandidate = true;
            else if (handle instanceof OfflineBatchValue) offlineRow = true;
            else if (handle instanceof RequestBatchValue) onlineRequest = true;
            else if (handle instanceof CandidateBatchValue) onlineCandidate = true;
        }
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
        // 在调用 Kernel 前统一检查列长，避免越界被误报为某个算子的业务异常。
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
            // 在线候选批中，共享请求值按 candidate → group 映射广播到所属候选行。
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
        // groupIndex 同时参与序列物化复用和错误定位；离线行没有请求组，因此使用 -1。
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

    private static final class SequenceAdaptingBatchColumn implements BatchColumn {
        private final BatchColumn delegate;
        private final RuntimeBatchLayout layout;
        private final SequenceViewInputMode mode;
        private final Map<Integer, IdentityHashMap<OperatorSequence, Object>> materializedByGroup;

        private SequenceAdaptingBatchColumn(
                BatchColumn delegate,
                RuntimeBatchLayout layout,
                SequenceViewInputMode mode,
                Map<Integer, IdentityHashMap<OperatorSequence, Object>> materializedByGroup) {
            this.delegate = delegate;
            this.layout = layout;
            this.mode = mode;
            this.materializedByGroup = materializedByGroup;
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public Object valueAt(int rowIndex) {
            Object value = delegate.valueAt(rowIndex);
            if (mode == SequenceViewInputMode.DIRECT
                    || !(value instanceof OperatorSequence sequence)) {
                return value;
            }
            int groupIndex = layout.groupIndexAt(rowIndex);
            IdentityHashMap<OperatorSequence, Object> groupValues =
                    materializedByGroup.computeIfAbsent(
                            groupIndex, ignored -> new IdentityHashMap<>());
            return SequenceViewArgumentAdapter.adapt(sequence, mode, groupValues);
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
     * 衍生特征边界语义：算子成功返回后，若整个特征值为 null 或空容器，使用模型 dft；
     * 异常不会进入本方法，仍由节点执行边界原样上抛。默认值替换先于 DOUBLE 定宽，
     * 确保声明 DOUBLE、dft 为整数配置值时对外仍得到 Double（C6/C10）。
     */
    private static ValueHandle executeFeatureOutput(
            PhysicalNode node,
            ExecutionContext context,
            RuntimeNodeState state) {
        ValueHandle value = applyFeatureDefault(
                requireSingleInput(node, context),
                node.executorConfig().get("defaultValue"),
                node.logicalValueShape(),
                context.executionId(),
                state);
        return widenIntegralFeatureOutput(
                value, node.executorConfig().get("widenIntegralToDouble"));
    }

    private static ValueHandle applyFeatureDefault(
            ValueHandle handle,
            Object defaultValue,
            ValueShape logicalValueShape,
            String alignmentId,
            RuntimeNodeState state) {
        if (defaultValue == null) return handle;

        ValueHandle result = switch (handle) {
            case ScalarValue scalar -> isEmptyFeatureValue(scalar.value())
                    ? defaultHandle(defaultValue, logicalValueShape, alignmentId)
                    : handle;
            case ListSequenceValue sequence -> sequence.size() == 0
                    ? defaultHandle(defaultValue, ValueShape.SEQUENCE, sequence.alignmentId())
                    : handle;
            case SequenceValue sequence -> sequence.size() == 0
                    ? defaultHandle(defaultValue, ValueShape.SEQUENCE, alignmentId)
                    : handle;
            case CandidateVectorValue vector -> {
                List<?> replaced = replaceEmptyElements(
                        vector.values(), defaultValue, logicalValueShape);
                if (replaced == vector.values()) yield handle;
                @SuppressWarnings("unchecked")
                List<Object> objectValues = (List<Object>) replaced;
                yield new CandidateVectorValue(objectValues);
            }
            case OfflineBatchValue batch -> {
                List<?> replaced = replaceEmptyElements(
                        batch.values(), defaultValue, batch.elementShape());
                if (replaced == batch.values()) yield handle;
                @SuppressWarnings("unchecked")
                List<Object> objectValues = (List<Object>) replaced;
                yield OfflineBatchValue.owned(objectValues, batch.elementShape());
            }
            case RequestBatchValue batch -> {
                List<?> replaced = replaceEmptyElements(
                        batch.values(), defaultValue, batch.elementShape());
                if (replaced == batch.values()) yield handle;
                @SuppressWarnings("unchecked")
                List<Object> objectValues = (List<Object>) replaced;
                yield RequestBatchValue.owned(objectValues, batch.elementShape());
            }
            case CandidateBatchValue batch -> {
                List<?> replaced = replaceEmptyElements(
                        batch.values(), defaultValue, batch.elementShape());
                if (replaced == batch.values()) yield handle;
                @SuppressWarnings("unchecked")
                List<Object> objectValues = (List<Object>) replaced;
                yield CandidateBatchValue.owned(objectValues, batch.elementShape());
            }
            default -> handle;
        };
        if (result != handle) state.setFallbackUsed(true);
        return result;
    }

    /** Copy-on-write：批/候选中没有空元素时保持原容器引用。 */
    private static List<?> replaceEmptyElements(
            List<?> values,
            Object defaultValue,
            ValueShape elementShape) {
        List<Object> replaced = null;
        Object replacement = null;
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            boolean empty = isEmptyFeatureValue(value);
            if (empty && replacement == null) {
                replacement = defaultRawValue(defaultValue, elementShape);
            }
            Object normalized = empty ? replacement : value;
            if (replaced == null && empty) {
                replaced = new ArrayList<>(values.size());
                for (int prefix = 0; prefix < index; prefix++) {
                    replaced.add(values.get(prefix));
                }
            }
            if (replaced != null) replaced.add(normalized);
        }
        if (replaced == null) return values;
        return Collections.unmodifiableList(replaced);
    }

    private static ValueHandle defaultHandle(
            Object defaultValue,
            ValueShape valueShape,
            String alignmentId) {
        if (valueShape == ValueShape.SEQUENCE) {
            return new ListSequenceValue(
                    alignmentId, defaultSequenceValue(defaultValue));
        }
        return new ScalarValue(defaultValue);
    }

    private static Object defaultRawValue(Object defaultValue, ValueShape valueShape) {
        return valueShape == ValueShape.SEQUENCE
                ? defaultSequenceValue(defaultValue)
                : defaultValue;
    }

    /** 序列 dft 可直接是完整 List；标量 dft 则表示一个兜底元素。 */
    private static List<?> defaultSequenceValue(Object defaultValue) {
        if (defaultValue instanceof List<?> list) return list;
        return Collections.singletonList(defaultValue);
    }

    private static boolean isEmptyFeatureValue(Object value) {
        if (value == null) return true;
        if (value instanceof ScalarValue scalar) {
            return isEmptyFeatureValue(scalar.value());
        }
        if (value instanceof ListSequenceValue sequence) return sequence.size() == 0;
        if (value instanceof SequenceValue sequence) return sequence.size() == 0;
        if (value instanceof CharSequence text) return text.length() == 0;
        if (value instanceof Collection<?> collection) return collection.isEmpty();
        if (value instanceof Map<?, ?> map) return map.isEmpty();
        return value.getClass().isArray() && java.lang.reflect.Array.getLength(value) == 0;
    }

    /**
     * DOUBLE 边界定宽：覆盖 C6 声明 DOUBLE/推断 INT，以及推断为 DOUBLE 但算子保留
     * 胜出整数载体的场景；仅把整型载体统一为 Double，不按算子名特判（C10）。
     * 容器采用 copy-on-write，未发现整型载体时直接复用原句柄。
     */
    private static ValueHandle widenIntegralFeatureOutput(ValueHandle handle, Object enabled) {
        if (!Boolean.TRUE.equals(enabled)) return handle;

        return switch (handle) {
            case ScalarValue scalar -> {
                Object widened = widenIntegralToDouble(scalar.value());
                yield widened == scalar.value() ? handle : new ScalarValue(widened);
            }
            case ListSequenceValue sequence -> {
                List<?> widened = widenScalars(sequence.values());
                yield widened != sequence.values()
                        ? new ListSequenceValue(sequence.alignmentId(), widened)
                        : handle;
            }
            case CandidateVectorValue vector -> {
                List<?> widened = widenScalars(vector.values());
                if (widened == vector.values()) yield handle;
                // widenScalars 对 List<Object> 入参总是返回 List<Object>（未改写时原样透传，
                // 改写时内部就地构建）；这里按已知的实际运行时类型转换，避免再拷贝一次——
                // CandidateVectorValue 的构造器本身已经会拷贝一次。
                @SuppressWarnings("unchecked")
                List<Object> objectValues = (List<Object>) widened;
                yield new CandidateVectorValue(objectValues);
            }
            case OfflineBatchValue batch -> {
                List<?> widened = widenBatchValues(
                        batch.values(), batch.elementShape());
                if (widened == batch.values()) yield handle;
                @SuppressWarnings("unchecked")
                List<Object> objectValues = (List<Object>) widened;
                yield OfflineBatchValue.owned(objectValues, batch.elementShape());
            }
            case RequestBatchValue batch -> {
                List<?> widened = widenBatchValues(
                        batch.values(), batch.elementShape());
                if (widened == batch.values()) yield handle;
                @SuppressWarnings("unchecked")
                List<Object> objectValues = (List<Object>) widened;
                yield RequestBatchValue.owned(objectValues, batch.elementShape());
            }
            case CandidateBatchValue batch -> {
                List<?> widened = widenBatchValues(
                        batch.values(), batch.elementShape());
                if (widened == batch.values()) yield handle;
                @SuppressWarnings("unchecked")
                List<Object> objectValues = (List<Object>) widened;
                yield CandidateBatchValue.owned(objectValues, batch.elementShape());
            }
            default -> handle;
        };
    }

    /** Copy-on-write：没有整型载体时沿用原列表；首次发现后才复制已扫描前缀。 */
    private static List<?> widenScalars(List<?> values) {
        List<Object> widened = null;
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            Object normalized = widenIntegralToDouble(value);
            if (widened == null && normalized != value) {
                widened = new ArrayList<>(values.size());
                for (int prefix = 0; prefix < index; prefix++) {
                    widened.add(values.get(prefix));
                }
            }
            if (widened != null) widened.add(normalized);
        }
        if (widened == null) return values;
        return Collections.unmodifiableList(widened);
    }

    private static List<?> widenBatchValues(
            List<?> values,
            ValueShape elementShape) {
        if (elementShape != ValueShape.SEQUENCE) return widenScalars(values);

        IdentityHashMap<List<?>, List<?>> widenedSequences = null;
        List<?> lastSequence = null;
        Object lastResult = null;
        List<Object> widened = null;
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            Object normalized;
            if (value instanceof List<?> sequence) {
                if (sequence == lastSequence) {
                    // 最常见的重复模式：连续多行复用同一引用（如缺失行统一回退到同一个默认
                    // 序列），O(1) 直接命中，不必等缓存分配。
                    normalized = lastResult;
                } else {
                    List<?> cached = widenedSequences == null
                            ? null : widenedSequences.get(sequence);
                    if (cached != null) {
                        normalized = cached;
                    } else {
                        List<?> sequenceResult = widenScalars(sequence);
                        normalized = sequenceResult;
                        if (sequenceResult != sequence) {
                            if (widenedSequences == null) widenedSequences = new IdentityHashMap<>();
                            widenedSequences.put(sequence, sequenceResult);
                        } else if (widenedSequences != null) {
                            // 本批已经确认存在需要改写的序列（缓存已分配）：顺带缓存“确认无需
                            // 改写”的结果，避免同一引用非连续复用时仍反复全量扫描；未分配缓存的
                            // 全干净批次不为此付出额外的 Map 开销。
                            widenedSequences.put(sequence, sequence);
                        }
                    }
                    lastSequence = sequence;
                    lastResult = normalized;
                }
            } else {
                normalized = widenIntegralToDouble(value);
            }
            if (widened == null && normalized != value) {
                widened = new ArrayList<>(values.size());
                for (int prefix = 0; prefix < index; prefix++) {
                    widened.add(values.get(prefix));
                }
            }
            if (widened != null) widened.add(normalized);
        }
        if (widened == null) return values;
        return Collections.unmodifiableList(widened);
    }

    private static Object widenIntegralToDouble(Object value) {
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof java.math.BigInteger) {
            return Double.valueOf(((Number) value).doubleValue());
        }
        return value;
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
            // list 可能是配置里长期共享的字面量，不能跳过拷贝；但 CandidateVectorValue 的紧凑
            // 构造器本身已经会拷贝一次，这里不必再预先拷贝一遍，用强转直接交给它拷贝即可。
            @SuppressWarnings("unchecked")
            List<Object> objectList = (List<Object>) list;
            return new CandidateVectorValue(objectList);
        }
        return new ScalarValue(value);
    }
}
