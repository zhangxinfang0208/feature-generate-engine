package com.example.featuredag.api;

import com.example.featuredag.config.FeatureConfigLoader;
import com.example.featuredag.config.FeatureConfigMapper;
import com.example.featuredag.config.FeatureOutputDescriptor;
import com.example.featuredag.config.FeatureSetConfig;
import com.example.featuredag.config.MappedFeatureSet;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.logical.LogicalDagPrinter;
import com.example.featuredag.operator.OperatorDefinition;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.physical.ExecutorType;
import com.example.featuredag.physical.PhysicalNode;
import com.example.featuredag.physical.PhysicalPlan;
import com.example.featuredag.physical.PhysicalPlanner;
import com.example.featuredag.physical.PhysicalPlanPrinter;
import com.example.featuredag.physical.rewrite.PhysicalRewriteRegistry;
import com.example.featuredag.planning.LogicalDagOptimizer;
import com.example.featuredag.runtime.CandidateBatchValue;
import com.example.featuredag.runtime.CandidateVectorValue;
import com.example.featuredag.runtime.DagRuntime;
import com.example.featuredag.runtime.ExecutionContext;
import com.example.featuredag.runtime.ExecutionDiagnostics;
import com.example.featuredag.runtime.ExecutionPhase;
import com.example.featuredag.runtime.ExecutionResult;
import com.example.featuredag.runtime.ExecutionStatus;
import com.example.featuredag.runtime.FeatureEvaluationException;
import com.example.featuredag.runtime.ListSequenceValue;
import com.example.featuredag.runtime.NodeExecutionSnapshot;
import com.example.featuredag.runtime.ObservabilityOptions;
import com.example.featuredag.runtime.ObservationDetailLevel;
import com.example.featuredag.runtime.OfflineBatchValue;
import com.example.featuredag.runtime.PhysicalExecutorRegistry;
import com.example.featuredag.runtime.RequestBatchValue;
import com.example.featuredag.runtime.RuntimeNodeExecutionException;
import com.example.featuredag.runtime.RuntimeNodeState;
import com.example.featuredag.runtime.RuntimeObservabilityController;
import com.example.featuredag.runtime.RuntimeObserver;
import com.example.featuredag.runtime.RuntimeTraceObserver;
import com.example.featuredag.runtime.SequenceIndexRegistry;
import com.example.featuredag.runtime.SequenceValue;
import com.example.featuredag.runtime.ValueHandle;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class FeatureDagEngine {
    private final ExecutionEnvironment environment;
    private final String featureSetName;
    private final String version;
    private final String planId;
    private final List<FeatureOutputDescriptor> outputs;
    private final LogicalDag logicalDag;
    private final PhysicalPlan plan;
    private final DagRuntime runtime;
    private final FeatureInputDecoder inputDecoder;
    private final FeatureOutputEncoder outputEncoder;
    private final RuntimeObservabilityController observabilityController;
    private final RuntimeObserver runtimeObserver;
    private final RuntimeTraceObserver runtimeTraceObserver;

    private FeatureDagEngine(
            ExecutionEnvironment environment,
            MappedFeatureSet mapped,
            String planId,
            LogicalDag logicalDag,
            PhysicalPlan plan,
            DagRuntime runtime,
            FeatureInputDecoder inputDecoder,
            FeatureOutputEncoder outputEncoder,
            RuntimeObservabilityController observabilityController,
            RuntimeObserver runtimeObserver,
            RuntimeTraceObserver runtimeTraceObserver) {
        this.environment = environment;
        this.featureSetName = mapped.featureSetName();
        this.version = mapped.version();
        this.planId = planId;
        this.outputs = mapped.outputs();
        this.logicalDag = Objects.requireNonNull(logicalDag, "logicalDag");
        this.plan = plan;
        this.runtime = runtime;
        this.inputDecoder = inputDecoder;
        this.outputEncoder = outputEncoder;
        this.observabilityController = Objects.requireNonNull(
                observabilityController, "observabilityController");
        this.runtimeObserver = Objects.requireNonNull(runtimeObserver, "runtimeObserver");
        this.runtimeTraceObserver = Objects.requireNonNull(
                runtimeTraceObserver, "runtimeTraceObserver");
    }

    public static FeatureDagEngine init(Path configFile, InitOptions options) {
        Objects.requireNonNull(options, "options");
        try {
            return initialize(FeatureConfigLoader.load(configFile), options);
        } catch (FeatureDagInitializationException error) {
            throw error;
        } catch (RuntimeException error) {
            throw initializationFailure(error, null, null, options.planId());
        }
    }

    public static FeatureDagEngine init(String configJson, InitOptions options) {
        Objects.requireNonNull(options, "options");
        try {
            return initialize(FeatureConfigLoader.load(configJson), options);
        } catch (FeatureDagInitializationException error) {
            throw error;
        } catch (RuntimeException error) {
            throw initializationFailure(error, null, null, options.planId());
        }
    }

    /**
     * 推理入口：按引擎环境路由到离线/在线执行；
     * 请求类型不匹配或执行期异常统一包装为 FeatureGenerationException 抛出。
     */
    public GenerateResult generate(GenerateRequest request) {
        Objects.requireNonNull(request, "request");
        int groupCount = request instanceof OnlineGenerateRequest ? 1 : 0;
        int candidateCount = request instanceof OnlineGenerateRequest online
                ? online.candidates().size()
                : 0;
        int offlineRowCount = request instanceof OfflineGenerateRequest ? 1 : 0;
        ExecutionObservation observation = startObservation(
                request.executionId(), groupCount, candidateCount, offlineRowCount);
        try {
            if (environment == ExecutionEnvironment.OFFLINE) {
                if (!(request instanceof OfflineGenerateRequest offline)) {
                    throw new IllegalArgumentException("OFFLINE engine requires OfflineGenerateRequest");
                }
                return generateOffline(offline, observation);
            }
            if (!(request instanceof OnlineGenerateRequest online)) {
                throw new IllegalArgumentException("ONLINE engine requires OnlineGenerateRequest");
            }
            return generateOnline(online, observation);
        } catch (FeatureGenerationException error) {
            markFailure(observation, error);
            throw error;
        } catch (RuntimeException error) {
            markFailure(observation, error);
            throw generationFailure(request.executionId(), error);
        } finally {
            publishObservation(observation);
        }
    }

    /**
     * 离线批推理：一次解码整批 RAW 行并只遍历一次物理计划，结果顺序与输入行一致。
     */
    public OfflineBatchGenerateResult generateBatch(OfflineBatchGenerateRequest request) {
        Objects.requireNonNull(request, "request");
        ExecutionObservation observation = startObservation(
                request.executionId(), 0, 0, request.rows().size());
        try {
            if (environment != ExecutionEnvironment.OFFLINE) {
                throw new FeatureGenerationException(
                        "ONLINE engine does not support OfflineBatchGenerateRequest",
                        planId, request.executionId(), null, null);
            }
            return generateOfflineBatch(request, observation);
        } catch (FeatureGenerationException error) {
            markFailure(observation, error);
            throw error;
        } catch (RuntimeException error) {
            markFailure(observation, error);
            throw generationFailure(request.executionId(), error);
        } finally {
            publishObservation(observation);
        }
    }

    /**
     * 在线分组批推理：每个 group 保持独立的共享输入和候选边界，整批只遍历一次物理计划。
     */
    public OnlineBatchGenerateResult generateBatch(OnlineBatchGenerateRequest request) {
        Objects.requireNonNull(request, "request");
        int candidateCount = request.groups().stream()
                .mapToInt(group -> group.candidates().size())
                .sum();
        ExecutionObservation observation = startObservation(
                request.executionId(), request.groups().size(), candidateCount, 0);
        try {
            if (environment != ExecutionEnvironment.ONLINE) {
                throw new FeatureGenerationException(
                        "OFFLINE engine does not support OnlineBatchGenerateRequest",
                        planId, request.executionId(), null, null);
            }
            return generateOnlineBatch(request, observation);
        } catch (FeatureGenerationException error) {
            markFailure(observation, error);
            throw error;
        } catch (RuntimeException error) {
            markFailure(observation, error);
            throw generationFailure(request.executionId(), error);
        } finally {
            publishObservation(observation);
        }
    }

    public String featureSetName() { return featureSetName; }
    public String version() { return version; }
    public String planId() { return planId; }
    public ExecutionEnvironment environment() { return environment; }

    /** 返回初始化后不可变的逻辑 DAG 文本，适合本地调测打印。 */
    public String describeLogicalDag() { return LogicalDagPrinter.print(logicalDag); }

    /** 返回运行时实际消费的物理计划文本，包含槽位、执行器和融合信息。 */
    public String describePhysicalPlan() { return PhysicalPlanPrinter.print(plan); }

    /**
     * 离线推理（单行）：解码整行源值 → 执行物理计划 → 按输出描述逐个编码；
     * 任一输出特征失败即整体失败，并在异常中带上特征名定位。
     */
    private GenerateResult generateOffline(
            OfflineGenerateRequest request,
            ExecutionObservation observation) {
        ExecutionContext context = measure(
                observation,
                ExecutionPhase.DECODE,
                () -> ExecutionContext.offlineRow(
                        request.executionId(), inputDecoder.decodeOffline(request.rowValues())));
        attachContext(observation, context);
        ExecutionResult execution = executeRuntime(context, observation);
        return measure(observation, ExecutionPhase.ENCODE, () -> {
            Map<String, List<?>> result = new LinkedHashMap<>();
            for (FeatureOutputDescriptor output : outputs) {
                try {
                    ValueHandle value = execution.feature(output.featureName());
                    result.put(
                            output.storeName(), outputEncoder.encode(output.featureName(), value));
                } catch (RuntimeException error) {
                    throw new FeatureGenerationException(
                            error.getMessage(), planId, request.executionId(), output.featureName(), error);
                }
            }
            return new GenerateResult(request.executionId(), result, List.of());
        });
    }

    private OfflineBatchGenerateResult generateOfflineBatch(
            OfflineBatchGenerateRequest request,
            ExecutionObservation observation) {
        // 整批只创建一个上下文并遍历一次物理计划；各源节点和算子通过 OfflineBatchValue 保持行对齐。
        ExecutionContext context = measure(
                observation,
                ExecutionPhase.DECODE,
                () -> ExecutionContext.offlineBatch(
                        request.executionId(), inputDecoder.decodeOfflineBatch(request.rows())));
        attachContext(observation, context);
        ExecutionResult execution = executeRuntime(context, observation);
        return measure(observation, ExecutionPhase.ENCODE, () -> {
            List<Map<String, List<?>>> rows = new ArrayList<>(request.rows().size());
            for (int index = 0; index < request.rows().size(); index++) {
                rows.add(new LinkedHashMap<>());
            }
            for (FeatureOutputDescriptor output : outputs) {
                try {
                    ValueHandle value = execution.feature(output.featureName());
                    if (value instanceof OfflineBatchValue batch) {
                        // 批输出逐行回填；行数不一致意味着某个 Kernel 破坏了 Batch 等价性约束。
                        if (batch.size() != rows.size()) {
                            throw new IllegalStateException(
                                    "Offline batch output size " + batch.size()
                                            + " does not match input size " + rows.size());
                        }
                        for (int index = 0; index < batch.size(); index++) {
                            rows.get(index).put(
                                    output.storeName(),
                                    outputEncoder.encodeBatchElement(
                                            output.featureName(), batch.valueAt(index)));
                        }
                    } else {
                        // 与行无关的常量结果广播到每一行，不要求上游人为制造重复批值。
                        List<?> encoded = outputEncoder.encode(output.featureName(), value);
                        for (Map<String, List<?>> row : rows) {
                            row.put(output.storeName(), encoded);
                        }
                    }
                } catch (RuntimeException error) {
                    throw new FeatureGenerationException(
                            error.getMessage(), planId, request.executionId(), output.featureName(), error);
                }
            }
            return new OfflineBatchGenerateResult(request.executionId(), rows);
        });
    }

    /**
     * 在线推理（请求级）：共享源值与候选表一并解码后执行；
     * 候选向量型输出按候选下标展开为逐候选结果，标量型输出进入共享结果。
     */
    private GenerateResult generateOnline(
            OnlineGenerateRequest request,
            ExecutionObservation observation) {
        ExecutionContext context = measure(
                observation,
                ExecutionPhase.DECODE,
                () -> ExecutionContext.onlineRequest(
                        request.executionId(),
                        inputDecoder.decodeOnlineShared(request.sharedValues()),
                        inputDecoder.decodeOnlineCandidates(request.candidates())));
        attachContext(observation, context);
        ExecutionResult execution = executeRuntime(context, observation);
        return measure(observation, ExecutionPhase.ENCODE, () -> {
            Map<String, List<?>> sharedResults = new LinkedHashMap<>();
            List<Map<String, List<?>>> candidateResults =
                    new ArrayList<>(request.candidates().size());
            for (int index = 0; index < request.candidates().size(); index++) {
                candidateResults.add(new LinkedHashMap<>());
            }
            for (FeatureOutputDescriptor output : outputs) {
                try {
                    ValueHandle value = execution.feature(output.featureName());
                    if (value instanceof CandidateVectorValue vector) {
                        if (vector.size() != candidateResults.size()) {
                            throw new IllegalStateException(
                                    "Candidate output size " + vector.size()
                                            + " does not match input size " + candidateResults.size());
                        }
                        for (int index = 0; index < vector.size(); index++) {
                            candidateResults.get(index).put(
                                    output.storeName(),
                                    outputEncoder.encodeCandidateElement(
                                            output.featureName(), vector.valueAt(index)));
                        }
                    } else {
                        sharedResults.put(
                                output.storeName(), outputEncoder.encode(output.featureName(), value));
                    }
                } catch (RuntimeException error) {
                    throw new FeatureGenerationException(
                            error.getMessage(), planId, request.executionId(), output.featureName(), error);
                }
            }
            return new GenerateResult(request.executionId(), sharedResults, candidateResults);
        });
    }

    private OnlineBatchGenerateResult generateOnlineBatch(
            OnlineBatchGenerateRequest request,
            ExecutionObservation observation) {
        List<OnlineRequestGroup> groups = request.groups();
        // API 边界保持分组结构；ExecutionContext 内部展平候选，以便物理计划只遍历一次。
        ExecutionContext context = measure(
                observation,
                ExecutionPhase.DECODE,
                () -> ExecutionContext.onlineBatch(
                        request.executionId(),
                        groups.stream().map(OnlineRequestGroup::executionId).toList(),
                        inputDecoder.decodeOnlineSharedBatch(groups),
                        inputDecoder.decodeOnlineCandidateBatch(groups)));
        attachContext(observation, context);
        ExecutionResult execution = executeRuntime(context, observation);

        return measure(observation, ExecutionPhase.ENCODE, () -> {
            List<Map<String, List<?>>> sharedResults = new ArrayList<>(groups.size());
            List<List<Map<String, List<?>>>> candidateResults = new ArrayList<>(groups.size());
            for (OnlineRequestGroup group : groups) {
                sharedResults.add(new LinkedHashMap<>());
                List<Map<String, List<?>>> groupCandidates =
                        new ArrayList<>(group.candidates().size());
                for (int index = 0; index < group.candidates().size(); index++) {
                    groupCandidates.add(new LinkedHashMap<>());
                }
                candidateResults.add(groupCandidates);
            }

            for (FeatureOutputDescriptor output : outputs) {
                try {
                    ValueHandle value = execution.feature(output.featureName());
                    if (value instanceof RequestBatchValue batch) {
                        if (batch.size() != groups.size()) {
                            throw new IllegalStateException(
                                    "Online request batch output size " + batch.size()
                                            + " does not match group size " + groups.size());
                        }
                        for (int groupIndex = 0; groupIndex < batch.size(); groupIndex++) {
                            sharedResults.get(groupIndex).put(
                                    output.storeName(),
                                    outputEncoder.encodeBatchElement(
                                            output.featureName(), batch.valueAt(groupIndex)));
                        }
                    } else if (value instanceof CandidateBatchValue batch) {
                        if (batch.size() != context.candidateCount()) {
                            throw new IllegalStateException(
                                    "Online candidate batch output size " + batch.size()
                                            + " does not match candidate size "
                                            + context.candidateCount());
                        }
                        for (int candidateIndex = 0;
                                candidateIndex < batch.size();
                                candidateIndex++) {
                            // 用上下文保存的组边界把扁平候选结果还原为调用方传入的分组与组内顺序。
                            int groupIndex = context.candidateGroupIndex(candidateIndex);
                            int indexInGroup = context.candidateIndexInGroup(candidateIndex);
                            candidateResults.get(groupIndex).get(indexInGroup).put(
                                    output.storeName(),
                                    outputEncoder.encodeBatchElement(
                                            output.featureName(), batch.valueAt(candidateIndex)));
                        }
                    } else if (value instanceof CandidateVectorValue
                            || value instanceof OfflineBatchValue) {
                        throw new IllegalStateException(
                                "Unexpected output handle for online batch: "
                                        + value.getClass().getSimpleName());
                    } else {
                        List<?> encoded = outputEncoder.encode(output.featureName(), value);
                        for (Map<String, List<?>> groupResult : sharedResults) {
                            groupResult.put(output.storeName(), encoded);
                        }
                    }
                } catch (RuntimeException error) {
                    throw new FeatureGenerationException(
                            error.getMessage(), planId, request.executionId(), output.featureName(), error);
                }
            }

            List<GenerateResult> groupResults = new ArrayList<>(groups.size());
            for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
                groupResults.add(new GenerateResult(
                        groups.get(groupIndex).executionId(),
                        sharedResults.get(groupIndex),
                        candidateResults.get(groupIndex)));
            }
            return new OnlineBatchGenerateResult(request.executionId(), groupResults);
        });
    }

    private ExecutionObservation startObservation(
            String executionId,
            int groupCount,
            int candidateCount,
            int offlineRowCount) {
        // NOOP 或完全关闭采集时不分配请求级观测对象，核心执行路径只多一次空判断。
        if (runtimeObserver == RuntimeObserver.NOOP) return null;
        ObservabilityOptions options = observabilityController.options();
        if (!options.canCaptureAnyRequest()) return null;
        return new ExecutionObservation(
                executionId,
                groupCount,
                candidateCount,
                offlineRowCount,
                options,
                deterministicSample(executionId, options.sampleRate()));
    }

    private ExecutionResult executeRuntime(
            ExecutionContext context,
            ExecutionObservation observation) {
        try {
            ExecutionResult result = measure(
                    observation,
                    ExecutionPhase.RUNTIME,
                    () -> runtime.execute(plan, context));
            publishRuntimeTrace(context.executionId(), result);
            return result;
        } catch (RuntimeException error) {
            // 失败时也发布已完成节点，便于从最后一个 FAILED 节点定位参数和根因。
            publishRuntimeTrace(
                    context.executionId(),
                    new ExecutionResult(
                            Map.of(),
                            context.nodeStates(),
                            context.runtimeCache().snapshot()));
            throw attachRuntimeNodeContext(context, error);
        }
    }

    private RuntimeException attachRuntimeNodeContext(
            ExecutionContext context,
            RuntimeException error) {
        // PR#21 兜底未命中：失败经 FailedValueHandle 在特征边界转成 FeatureEvaluationException，
        // 节点状态不再置 FAILED，改从异常携带的物理节点精确定位（PR#27）。
        if (error instanceof FeatureEvaluationException featureFailure) {
            PhysicalNode node = planNodeById(featureFailure.physicalNodeId());
            if (node == null) return error;
            List<String> affected = plan.affectedFeatureNames(node.physicalNodeId());
            if (affected.isEmpty()) affected = List.of(featureFailure.featureName());
            return new RuntimeNodeExecutionException(node, affected, error);
        }
        for (PhysicalNode node : plan.nodes()) {
            RuntimeNodeState state = context.nodeStates().get(node.physicalNodeId());
            if (state == null || state.status() != ExecutionStatus.FAILED) continue;
            if (node.executorType() != ExecutorType.GENERIC_OPERATOR
                    && node.executorType() != ExecutorType.SPECIALIZED) {
                return error;
            }
            // L2→API：公共边界用规划期目标集合补齐错误上下文，Runtime 仍保留原始异常协议（C1/C8-C10）。
            return new RuntimeNodeExecutionException(
                    node, plan.affectedFeatureNames(node.physicalNodeId()), error);
        }
        return error;
    }

    private PhysicalNode planNodeById(String physicalNodeId) {
        for (PhysicalNode node : plan.nodes()) {
            if (node.physicalNodeId().equals(physicalNodeId)) return node;
        }
        return null;
    }

    private FeatureGenerationException generationFailure(
            String executionId,
            RuntimeException error) {
        if (error instanceof RuntimeNodeExecutionException nodeFailure) {
            return FeatureGenerationException.forFeatureNames(
                    nodeFailure.getMessage(),
                    planId,
                    executionId,
                    nodeFailure.affectedFeatureNames(),
                    nodeFailure);
        }
        // PR#21 兜底未命中路径：FeatureEvaluationException 自带单特征现场，保持归因不丢。
        String featureName = error instanceof FeatureEvaluationException featureFailure
                ? featureFailure.featureName()
                : null;
        return new FeatureGenerationException(
                error.getMessage(), planId, executionId, featureName, error);
    }

    private void publishRuntimeTrace(String executionId, ExecutionResult result) {
        if (runtimeTraceObserver == RuntimeTraceObserver.NOOP) return;
        try {
            runtimeTraceObserver.onExecutionFinished(executionId, plan, result);
        } catch (RuntimeException ignored) {
            // 调测出口与业务执行隔离：打印或自定义 sink 失败不得改变 generate 结果。
        }
    }

    private static <T> T measure(
            ExecutionObservation observation,
            ExecutionPhase phase,
            Supplier<T> operation) {
        if (observation == null) return operation.get();
        // 解码、运行、编码共享同一计时包装；异常先记录所属阶段，再原样交给上层转换。
        long start = System.nanoTime();
        try {
            return operation.get();
        } catch (RuntimeException error) {
            observation.markFailure(phase, error);
            throw error;
        } finally {
            observation.addDuration(phase, System.nanoTime() - start);
        }
    }

    private static void attachContext(
            ExecutionObservation observation,
            ExecutionContext context) {
        if (observation != null) observation.attachContext(context);
    }

    private static void markFailure(
            ExecutionObservation observation,
            Throwable error) {
        if (observation != null) observation.markFailure(ExecutionPhase.VALIDATION, error);
    }

    private void publishObservation(ExecutionObservation observation) {
        if (observation == null) return;
        long totalDurationNanos = observation.elapsedNanos();
        // 普通未采样请求直接丢弃；失败和慢请求可由配置强制保留。
        if (!observation.shouldPublish(totalDurationNanos)) return;
        try {
            runtimeObserver.onExecutionCompleted(observation.snapshot(
                    planId,
                    featureSetName,
                    version,
                    environment,
                    plan,
                    totalDurationNanos));
        } catch (RuntimeException ignored) {
            // 观测出口与业务执行隔离：指标适配器故障不得改变 generate 结果。
        }
    }

    private boolean deterministicSample(String executionId, double sampleRate) {
        if (sampleRate <= 0.0) return false;
        if (sampleRate >= 1.0) return true;
        // planId + executionId 的稳定哈希让同一请求在重试和多实例间得到一致采样结论。
        long hash = hashText(0xcbf29ce484222325L, planId);
        hash ^= 0xff;
        hash *= 0x100000001b3L;
        hash = hashText(hash, executionId);
        double normalized = (hash >>> 11) * 0x1.0p-53;
        return normalized < sampleRate;
    }

    private static long hashText(long initial, String value) {
        long hash = initial;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static FeatureDagEngine initialize(FeatureSetConfig config, InitOptions options) {
        String featureSetName = config.featureSetName();
        String version = config.version();
        String configuredPlanId = options.planId();
        try {
            MappedFeatureSet mapped = FeatureConfigMapper.map(
                    config,
                    options.targetFeatures(),
                    options.rawFeatureScopes(),
                    options.defaultRawFeatureScopes());
            String planId = configuredPlanId == null
                    ? mapped.featureSetName() + "-" + mapped.version() + "-"
                            + options.environment().name().toLowerCase(Locale.ROOT)
                    : configuredPlanId;
            OperatorRegistry operators = OperatorRegistry.standard();
            for (OperatorDefinition extension : options.operatorExtensions()) {
                operators.register(extension);
            }
            PhysicalRewriteRegistry rewriteRules = PhysicalRewriteRegistry.standard();
            SequenceIndexRegistry sequenceIndexes = SequenceIndexRegistry.standard();
            PhysicalExecutorRegistry executors = PhysicalExecutorRegistry.standard(sequenceIndexes);
            // 各扩展点共用同一组注册实例，保证逻辑推断、物理改写和运行执行看到一致能力集合。
            // C1：按「定义 → 逻辑 → 规划 → 物理 → 运行时」逐层构建，各层产物依次作为下一层的输入
            LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), operators)
                    .build(mapped.definitions(), mapped.targetFeatures());
            PhysicalPlan plan = new PhysicalPlanner(operators, rewriteRules).plan(
                    new LogicalDagOptimizer(operators).analyze(dag), options.environment(), planId);
            // 在引擎发布前验证全部专用 executorId 和配置，使初始化失败而不是首个请求失败。
            executors.validate(plan);
            FeatureInputDecoder inputDecoder = FeatureInputDecoder.from(dag);
            FeatureOutputEncoder outputEncoder = FeatureOutputEncoder.from(
                    dag, mapped.outputs());
            return new FeatureDagEngine(
                    options.environment(), mapped, planId, dag, plan,
                    new DagRuntime(operators, executors),
                    inputDecoder,
                    outputEncoder,
                    options.observabilityController(),
                    options.runtimeObserver(),
                    options.runtimeTraceObserver());
        } catch (RuntimeException error) {
            throw initializationFailure(
                    error, featureSetName, version, configuredPlanId);
        }
    }

    private static FeatureDagInitializationException initializationFailure(
            RuntimeException error,
            String featureSetName,
            String version,
            String planId) {
        return new FeatureDagInitializationException(
                error.getMessage(), featureSetName, version, planId, null, error);
    }

    private static final class ExecutionObservation {
        private final String executionId;
        private final int groupCount;
        private final int candidateCount;
        private final int offlineRowCount;
        private final ObservabilityOptions options;
        private final boolean sampled;
        private final long startNanos = System.nanoTime();
        private long decodeDurationNanos;
        private long runtimeDurationNanos;
        private long encodeDurationNanos;
        private ExecutionContext context;
        private ExecutionPhase failurePhase = ExecutionPhase.NONE;
        private Throwable failure;

        private ExecutionObservation(
                String executionId,
                int groupCount,
                int candidateCount,
                int offlineRowCount,
                ObservabilityOptions options,
                boolean sampled) {
            this.executionId = executionId;
            this.groupCount = groupCount;
            this.candidateCount = candidateCount;
            this.offlineRowCount = offlineRowCount;
            this.options = options;
            this.sampled = sampled;
        }

        private void attachContext(ExecutionContext value) {
            context = value;
        }

        private void addDuration(ExecutionPhase phase, long durationNanos) {
            switch (phase) {
                case DECODE -> decodeDurationNanos += durationNanos;
                case RUNTIME -> runtimeDurationNanos += durationNanos;
                case ENCODE -> encodeDurationNanos += durationNanos;
                case NONE, VALIDATION -> {
                    // VALIDATION 没有单独计时，包含在 totalDurationNanos 中。
                }
            }
        }

        private void markFailure(ExecutionPhase phase, Throwable error) {
            // 只保留最初失败阶段；后续包装异常不能覆盖最接近根因的位置。
            if (failure == null) {
                failurePhase = phase;
                failure = error;
            }
        }

        private long elapsedNanos() {
            return System.nanoTime() - startNanos;
        }

        private boolean shouldPublish(long totalDurationNanos) {
            return sampled
                    || (failure != null && options.captureFailuresAlways())
                    || isSlow(totalDurationNanos);
        }

        private boolean isSlow(long totalDurationNanos) {
            long threshold = options.slowRequestThresholdNanos();
            return threshold > 0 && totalDurationNanos >= threshold;
        }

        private ExecutionDiagnostics snapshot(
                String planId,
                String featureSetName,
                String version,
                ExecutionEnvironment environment,
                PhysicalPlan plan,
                long totalDurationNanos) {
            ObservationDetailLevel detailLevel = options.detailLevel();
            List<NodeExecutionSnapshot> nodes = new ArrayList<>();
            // 节点级快照按物理计划顺序生成，便于直接还原执行链；不复制结果值或 Throwable。
            if (context != null && detailLevel.includesNodes()) {
                for (PhysicalNode node : plan.nodes()) {
                    RuntimeNodeState state = context.nodeStates().get(node.physicalNodeId());
                    if (state == null) continue;
                    nodes.add(new NodeExecutionSnapshot(
                            node.physicalNodeId(),
                            node.executorId(),
                            state.operatorInvocationKind(),
                            state.batchDomain(),
                            state.batchRowCount(),
                            node.executionStage(),
                            node.cachePolicy(),
                            state.status(),
                            state.durationNanos(),
                            state.cacheStats(),
                            state.dedupInputCount(),
                            state.uniqueInputCount(),
                            state.fallbackUsed(),
                            state.operatorFailureCount(),
                            state.fallbackCount(),
                            state.error() == null ? null : state.error().getClass().getName()));
                }
            }
            int logicalNodeCount = plan.nodes().stream()
                    .mapToInt(node -> node.logicalNodeIds().size())
                    .sum();
            // 一个物理节点消费多个逻辑节点即视为融合，用于观测优化是否实际生效。
            int fusedNodeCount = (int) plan.nodes().stream()
                    .filter(node -> node.logicalNodeIds().size() > 1)
                    .count();
            SequenceMetrics sequenceMetrics = new SequenceMetrics();
            if (context != null) {
                // 只从序列源节点统计输入规模，避免下游多个视图重复计算同一业务序列。
                for (PhysicalNode node : plan.nodes()) {
                    if (node.executorType() != ExecutorType.SOURCE_BINDING
                            || node.logicalValueShape() != ValueShape.SEQUENCE) {
                        continue;
                    }
                    RuntimeNodeState state = context.nodeStates().get(node.physicalNodeId());
                    if (state != null) collectSequenceMetrics(state.resultHandle(), sequenceMetrics);
                }
            }
            return new ExecutionDiagnostics(
                    planId,
                    featureSetName,
                    version,
                    executionId,
                    environment,
                    failure == null ? ExecutionStatus.SUCCESS : ExecutionStatus.FAILED,
                    failurePhase,
                    failure == null ? null : failure.getClass().getName(),
                    sampled,
                    isSlow(totalDurationNanos),
                    detailLevel,
                    totalDurationNanos,
                    decodeDurationNanos,
                    runtimeDurationNanos,
                    encodeDurationNanos,
                    groupCount,
                    candidateCount,
                    offlineRowCount,
                    sequenceMetrics.count,
                    sequenceMetrics.elementCount,
                    sequenceMetrics.maxLength,
                    plan.nodes().size(),
                    logicalNodeCount,
                    fusedNodeCount,
                    context == null || !detailLevel.includesCache()
                            ? Map.of()
                            : context.runtimeCache().snapshot(),
                    nodes);
        }

        private static void collectSequenceMetrics(
                Object value,
                SequenceMetrics metrics) {
            // 同时兼容单值和三种批句柄，递归统计批内每个序列元素。
            if (value == null) return;
            if (value instanceof SequenceValue sequence) {
                metrics.record(sequence.size());
                return;
            }
            if (value instanceof ListSequenceValue sequence) {
                metrics.record(sequence.size());
                return;
            }
            if (value instanceof OfflineBatchValue batch) {
                batch.values().forEach(element -> collectSequenceMetrics(element, metrics));
                return;
            }
            if (value instanceof RequestBatchValue batch) {
                batch.values().forEach(element -> collectSequenceMetrics(element, metrics));
                return;
            }
            if (value instanceof CandidateBatchValue batch) {
                batch.values().forEach(element -> collectSequenceMetrics(element, metrics));
                return;
            }
            if (value instanceof CandidateVectorValue vector) {
                vector.values().forEach(element -> collectSequenceMetrics(element, metrics));
                return;
            }
            if (value instanceof List<?> list) metrics.record(list.size());
        }

        private static final class SequenceMetrics {
            private int count;
            private long elementCount;
            private int maxLength;

            private void record(int size) {
                count++;
                elementCount += size;
                maxLength = Math.max(maxLength, size);
            }
        }
    }
}
