package com.example.featuredag.api;

import com.example.featuredag.config.FeatureConfigLoader;
import com.example.featuredag.config.FeatureConfigMapper;
import com.example.featuredag.config.FeatureOutputDescriptor;
import com.example.featuredag.config.FeatureSetConfig;
import com.example.featuredag.config.MappedFeatureSet;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.physical.PhysicalPlan;
import com.example.featuredag.physical.PhysicalPlanner;
import com.example.featuredag.physical.rewrite.PhysicalRewriteRegistry;
import com.example.featuredag.planning.LogicalDagOptimizer;
import com.example.featuredag.runtime.CandidateVectorValue;
import com.example.featuredag.runtime.DagRuntime;
import com.example.featuredag.runtime.ExecutionContext;
import com.example.featuredag.runtime.ExecutionResult;
import com.example.featuredag.runtime.OfflineBatchValue;
import com.example.featuredag.runtime.PhysicalExecutorRegistry;
import com.example.featuredag.runtime.SequenceIndexRegistry;
import com.example.featuredag.runtime.ValueHandle;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class FeatureDagEngine {
    private final ExecutionEnvironment environment;
    private final String featureSetName;
    private final String version;
    private final String planId;
    private final List<FeatureOutputDescriptor> outputs;
    private final PhysicalPlan plan;
    private final DagRuntime runtime;
    private final FeatureInputDecoder inputDecoder;
    private final FeatureOutputEncoder outputEncoder;

    private FeatureDagEngine(
            ExecutionEnvironment environment,
            MappedFeatureSet mapped,
            String planId,
            PhysicalPlan plan,
            DagRuntime runtime,
            FeatureInputDecoder inputDecoder,
            FeatureOutputEncoder outputEncoder) {
        this.environment = environment;
        this.featureSetName = mapped.featureSetName();
        this.version = mapped.version();
        this.planId = planId;
        this.outputs = mapped.outputs();
        this.plan = plan;
        this.runtime = runtime;
        this.inputDecoder = inputDecoder;
        this.outputEncoder = outputEncoder;
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
        try {
            if (environment == ExecutionEnvironment.OFFLINE) {
                if (!(request instanceof OfflineGenerateRequest offline)) {
                    throw new IllegalArgumentException("OFFLINE engine requires OfflineGenerateRequest");
                }
                return generateOffline(offline);
            }
            if (!(request instanceof OnlineGenerateRequest online)) {
                throw new IllegalArgumentException("ONLINE engine requires OnlineGenerateRequest");
            }
            return generateOnline(online);
        } catch (FeatureGenerationException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new FeatureGenerationException(
                    error.getMessage(), planId, request.executionId(), null, error);
        }
    }

    /**
     * 离线批推理：一次解码整批 RAW 行并只遍历一次物理计划，结果顺序与输入行一致。
     */
    public OfflineBatchGenerateResult generateBatch(OfflineBatchGenerateRequest request) {
        Objects.requireNonNull(request, "request");
        if (environment != ExecutionEnvironment.OFFLINE) {
            throw new FeatureGenerationException(
                    "ONLINE engine does not support OfflineBatchGenerateRequest",
                    planId, request.executionId(), null, null);
        }
        try {
            return generateOfflineBatch(request);
        } catch (FeatureGenerationException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new FeatureGenerationException(
                    error.getMessage(), planId, request.executionId(), null, error);
        }
    }

    public String featureSetName() { return featureSetName; }
    public String version() { return version; }
    public String planId() { return planId; }
    public ExecutionEnvironment environment() { return environment; }

    /**
     * 离线推理（单行）：解码整行源值 → 执行物理计划 → 按输出描述逐个编码；
     * 任一输出特征失败即整体失败，并在异常中带上特征名定位。
     */
    private GenerateResult generateOffline(OfflineGenerateRequest request) {
        ExecutionResult execution = runtime.execute(
                plan,
                ExecutionContext.offlineRow(
                        request.executionId(), inputDecoder.decodeOffline(request.rowValues())));
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
    }

    private OfflineBatchGenerateResult generateOfflineBatch(OfflineBatchGenerateRequest request) {
        ExecutionResult execution = runtime.execute(
                plan,
                ExecutionContext.offlineBatch(
                        request.executionId(), inputDecoder.decodeOfflineBatch(request.rows())));
        List<Map<String, List<?>>> rows = new ArrayList<>(request.rows().size());
        for (int index = 0; index < request.rows().size(); index++) {
            rows.add(new LinkedHashMap<>());
        }
        for (FeatureOutputDescriptor output : outputs) {
            try {
                ValueHandle value = execution.feature(output.featureName());
                if (value instanceof OfflineBatchValue batch) {
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
    }

    /**
     * 在线推理（请求级）：共享源值与候选表一并解码后执行；
     * 候选向量型输出按候选下标展开为逐候选结果，标量型输出进入共享结果。
     */
    private GenerateResult generateOnline(OnlineGenerateRequest request) {
        ExecutionResult execution = runtime.execute(
                plan,
                ExecutionContext.onlineRequest(
                        request.executionId(),
                        inputDecoder.decodeOnlineShared(request.sharedValues()),
                        inputDecoder.decodeOnlineCandidates(request.candidates())));
        Map<String, List<?>> sharedResults = new LinkedHashMap<>();
        List<Map<String, List<?>>> candidateResults = new ArrayList<>(request.candidates().size());
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
    }

    private static FeatureDagEngine initialize(FeatureSetConfig config, InitOptions options) {
        String featureSetName = config.featureSetName();
        String version = config.version();
        String configuredPlanId = options.planId();
        try {
            MappedFeatureSet mapped = FeatureConfigMapper.map(
                    config,
                    options.environment(),
                    options.targetFeatures(),
                    options.rawFeatureScopes(),
                    options.defaultRawFeatureScopes());
            String planId = configuredPlanId == null
                    ? mapped.featureSetName() + "-" + mapped.version() + "-"
                            + options.environment().name().toLowerCase(Locale.ROOT)
                    : configuredPlanId;
            OperatorRegistry operators = OperatorRegistry.standard();
            PhysicalRewriteRegistry rewriteRules = PhysicalRewriteRegistry.standard();
            SequenceIndexRegistry sequenceIndexes = SequenceIndexRegistry.standard();
            PhysicalExecutorRegistry executors = PhysicalExecutorRegistry.standard(sequenceIndexes);
            // C1：按「定义 → 逻辑 → 规划 → 物理 → 运行时」逐层构建，各层产物依次作为下一层的输入
            LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), operators)
                    .build(mapped.definitions(), mapped.targetFeatures());
            PhysicalPlan plan = new PhysicalPlanner(operators, rewriteRules).plan(
                    new LogicalDagOptimizer(operators).analyze(dag), options.environment(), planId);
            executors.validate(plan);
            FeatureInputDecoder inputDecoder = FeatureInputDecoder.from(dag);
            FeatureOutputEncoder outputEncoder = FeatureOutputEncoder.from(dag);
            return new FeatureDagEngine(
                    options.environment(), mapped, planId, plan, new DagRuntime(operators, executors),
                    inputDecoder, outputEncoder);
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
}
