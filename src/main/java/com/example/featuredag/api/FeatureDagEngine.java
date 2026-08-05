package com.example.featuredag.api;

import com.example.featuredag.config.FeatureConfigLoader;
import com.example.featuredag.config.FeatureConfigMapper;
import com.example.featuredag.config.FeatureOutputDescriptor;
import com.example.featuredag.config.FeatureSetConfig;
import com.example.featuredag.config.MappedFeatureSet;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.logical.SourceNode;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.physical.PhysicalPlan;
import com.example.featuredag.physical.PhysicalPlanner;
import com.example.featuredag.planning.LogicalDagOptimizer;
import com.example.featuredag.runtime.CandidateVectorValue;
import com.example.featuredag.runtime.DagRuntime;
import com.example.featuredag.runtime.ExecutionContext;
import com.example.featuredag.runtime.ExecutionResult;
import com.example.featuredag.runtime.ExternalValueMaterializer;
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
    private final ExternalValueMaterializer materializer;

    private FeatureDagEngine(
            ExecutionEnvironment environment,
            MappedFeatureSet mapped,
            String planId,
            PhysicalPlan plan,
            DagRuntime runtime) {
        this.environment = environment;
        this.featureSetName = mapped.featureSetName();
        this.version = mapped.version();
        this.planId = planId;
        this.outputs = mapped.outputs();
        this.plan = plan;
        this.runtime = runtime;
        this.materializer = new ExternalValueMaterializer();
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

    public String featureSetName() { return featureSetName; }
    public String version() { return version; }
    public String planId() { return planId; }
    public ExecutionEnvironment environment() { return environment; }

    private GenerateResult generateOffline(OfflineGenerateRequest request) {
        ExecutionResult execution = runtime.execute(
                plan,
                ExecutionContext.offlineRow(request.executionId(), request.rowValues()));
        Map<String, Object> result = new LinkedHashMap<>();
        for (FeatureOutputDescriptor output : outputs) {
            try {
                ValueHandle value = execution.feature(output.featureName());
                result.put(output.storeName(), materializer.materialize(value));
            } catch (RuntimeException error) {
                throw new FeatureGenerationException(
                        error.getMessage(), planId, request.executionId(), output.featureName(), error);
            }
        }
        return new GenerateResult(request.executionId(), result, List.of());
    }

    private GenerateResult generateOnline(OnlineGenerateRequest request) {
        ExecutionResult execution = runtime.execute(
                plan,
                ExecutionContext.onlineRequest(
                        request.executionId(), request.sharedValues(), request.candidates()));
        Map<String, Object> sharedResults = new LinkedHashMap<>();
        List<Map<String, Object>> candidateResults = new ArrayList<>(request.candidates().size());
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
                                output.storeName(), materializer.materializeRaw(vector.valueAt(index)));
                    }
                } else {
                    sharedResults.put(output.storeName(), materializer.materialize(value));
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
                    options.rawFeatureScopes());
            String planId = configuredPlanId == null
                    ? mapped.featureSetName() + "-" + mapped.version() + "-"
                            + options.environment().name().toLowerCase(Locale.ROOT)
                    : configuredPlanId;
            OperatorRegistry operators = OperatorRegistry.standard();
            LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), operators)
                    .build(mapped.definitions(), mapped.targetFeatures());
            if (options.environment() == ExecutionEnvironment.ONLINE) {
                for (var node : dag.nodes().values()) {
                    if (node instanceof SourceNode source
                            && mapped.unresolvedOnlineScopes().contains(source.featureName())) {
                        throw new IllegalArgumentException(
                                "Online raw feature is missing entity_scopes: " + source.featureName());
                    }
                }
            }
            PhysicalPlan plan = new PhysicalPlanner().plan(
                    new LogicalDagOptimizer().analyze(dag), options.environment(), planId);
            return new FeatureDagEngine(
                    options.environment(), mapped, planId, plan, new DagRuntime(operators));
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
