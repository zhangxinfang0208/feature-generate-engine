package com.example.featuredag;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.FeatureDagInitializationException;
import com.example.featuredag.api.FeatureGenerationException;
import com.example.featuredag.api.GenerateResult;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OfflineGenerateRequest;
import com.example.featuredag.api.OnlineGenerateRequest;
import com.example.featuredag.config.FeatureConfigLoader;
import com.example.featuredag.config.FeatureConfigMapper;
import com.example.featuredag.config.FeatureSetConfig;
import com.example.featuredag.config.MappedFeatureSet;
import com.example.featuredag.config.FeatureConfig;
import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.FeatureDefinition;
import com.example.featuredag.definition.OutputPolicy;
import com.example.featuredag.demo.ExampleFeatures;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.DagBuildException;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.logical.ValueShape;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.physical.ExecutionStage;
import com.example.featuredag.physical.ExecutorType;
import com.example.featuredag.physical.PhysicalNode;
import com.example.featuredag.physical.PhysicalPlan;
import com.example.featuredag.physical.PhysicalPlanner;
import com.example.featuredag.planning.LogicalDagOptimizer;
import com.example.featuredag.runtime.CandidateVectorValue;
import com.example.featuredag.runtime.BitmapSelection;
import com.example.featuredag.runtime.DagRuntime;
import com.example.featuredag.runtime.ExecutionContext;
import com.example.featuredag.runtime.ExecutionResult;
import com.example.featuredag.runtime.IndexSelection;
import com.example.featuredag.runtime.RangeSelection;
import com.example.featuredag.runtime.SequenceBlock;
import com.example.featuredag.runtime.SequenceEvent;
import com.example.featuredag.runtime.SequenceView;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

/** Dependency-free self test. Run with java -ea. */
public final class DagEngineSelfTest {
    public static void main(String[] args) throws Exception {
        testBusinessJsonParsing();
        testUnifiedFeatureJsonParsing();
        testLegacyDerivedFeaturesRejected();
        testIntermediateFeatureMapping();
        testOfflinePublicApi();
        testConfigPathInit();
        testOfflineSequenceMaterialization();
        testOnlinePublicApi();
        testOnlineEngineConcurrentReuse();
        testConfigurationAndRequestValidation();
        testBaseOutputPolicyIsValidated();
        testDeclaredScopeIsValidatedBeforeOverride();
        testDeclaredValueShapeAndScopeSemantics();
        testDagBusinessSemantics();
        testExecutionStagesAndTargetSelection();
        testCandidateCardinalityAndDefaults();
        testEmptySequenceAndOfflineOutputSet();
        testCandidateDeduplicationAndFusion();
        testSequenceSelectionStrategies();
        testOfflineOnlineConsistency();

        OperatorRegistry operators = OperatorRegistry.standard();
        LogicalDagBuilder builder = new LogicalDagBuilder(new ExpressionParser(), operators);
        LogicalDagOptimizer optimizer = new LogicalDagOptimizer();
        PhysicalPlanner planner = new PhysicalPlanner();
        DagRuntime runtime = new DagRuntime(operators);

        LogicalDag onlineDag = builder.build(ExampleFeatures.definitions(), ExampleFeatures.onlineTargets());
        PhysicalPlan onlinePlan = planner.plan(
                optimizer.analyze(onlineDag), ExecutionEnvironment.ONLINE, "online-test");
        assert onlineDag.featureOutputNodeIds().containsKey("user_click_score");
        assert onlineDag.featureOutputNodeIds().containsKey("item_price_log");
        assert !onlineDag.featureOutputNodeIds().containsKey("unused_feature");
        assert onlinePlan.nodes().stream().anyMatch(node -> node.executorType() == ExecutorType.COUNT_INDUSTRY_BATCH)
                : "Online plan should fuse extractIndustry + count";

        SequenceBlock sequence = sequence();
        Map<String, Object> shared = new LinkedHashMap<>();
        shared.put("user_click_count", 10);
        shared.put("user_seq1", sequence);
        List<Map<String, Object>> candidates = List.of(
                Map.of("item_industry", "industry1", "item_price", 100.0),
                Map.of("item_industry", "industry2", "item_price", 50.0),
                Map.of("item_industry", "industry1", "item_price", 80.0));
        ExecutionResult onlineResult = runtime.execute(
                onlinePlan, ExecutionContext.onlineRequest("request-test", shared, candidates));
        CandidateVectorValue counts = (CandidateVectorValue) onlineResult.feature("same_industry_count");
        assert counts.values().equals(List.of(3, 1, 3)) : counts.values();
        assert onlineResult.nodeStates().values().stream()
                .anyMatch(state -> state.dedupInputCount() == 3 && state.uniqueInputCount() == 2)
                : "Expected candidate industry dedup 3 -> 2";

        LogicalDag offlineDag = builder.build(ExampleFeatures.definitions(), ExampleFeatures.transformTargets());
        PhysicalPlan offlinePlan = planner.plan(
                optimizer.analyze(offlineDag), ExecutionEnvironment.OFFLINE, "offline-test");
        assert offlinePlan.nodes().stream().noneMatch(node -> node.executorType() == ExecutorType.COUNT_INDUSTRY_BATCH)
                : "Transform must retain same_industry_seq output";
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("user_click_count", 10);
        row.put("user_seq1", sequence);
        row.put("item_industry", "industry1");
        row.put("item_price", 100.0);
        ExecutionResult offlineResult = runtime.execute(
                offlinePlan, ExecutionContext.offlineRow("row-test", row));
        SequenceView view = (SequenceView) offlineResult.feature("same_industry_seq");
        assert view.size() == 3;
        assert view.baseBlock() == sequence : "SequenceView must share the original SequenceBlock";
        assert ((Number) offlineResult.feature("same_industry_count").raw()).intValue() == 3;

        System.out.println("All DAG engine self tests passed.");
    }

    private static void testBusinessJsonParsing() {
        String json = """
                {
                  "features": [{
                    "catalog": "/mock/dir",
                    "name": "price_type1",
                    "raw_name": "price_type",
                    "store_name": "price_type1",
                    "type": "STRING",
                    "feature_type": "sparse",
                    "dft": "missing",
                    "to_use": true,
                    "order": 3,
                    "is_feedback": "true",
                    "entity_scopes": ["ITEM"],
                    "future_business_field": "kept"
                  }, {
                    "name": "price_present",
                    "store_name": "price_present_out",
                    "type": "STRING",
                    "definition_type": "DERIVED",
                    "expression": "coalesce(price_type1, \\\"missing\\\")",
                    "to_use": true,
                    "output_policy": "OUTPUT",
                    "order": 10
                  }],
                  "feature_set_name": " test_001 ",
                  "version": "latest"
                }
                """;

        FeatureSetConfig config = FeatureConfigLoader.load(json);
        assert config.features().size() == 2 : config.features();
        FeatureConfig raw = config.features().getFirst();
        assert raw.name().equals("price_type1") : raw.name();
        assert raw.rawName().equals("price_type") : raw.rawName();
        assert Boolean.TRUE.equals(raw.isFeedback()) : raw.isFeedback();
        assert raw.additionalProperties().get("future_business_field").equals("kept")
                : raw.additionalProperties();
        assert config.features().get(1).outputPolicy().equals("OUTPUT");
        assert config.featureSetName().equals(" test_001 ") : config.featureSetName();
    }

    private static void testUnifiedFeatureJsonParsing() {
        FeatureSetConfig config = FeatureConfigLoader.load("""
                {
                  "features": [
                    {"name":"price","raw_name":"raw_price","type":"DOUBLE",
                     "definition_type":null,"value_shape":"SCALAR","future_business_field":"kept"},
                    {"name":"price_score","type":"DOUBLE","definition_type":"DERIVED",
                     "expression":"multiply(price, price)","output_policy":"OUTPUT"}
                  ],
                  "feature_set_name":"test_001","version":"latest"
                }
                """);
        assert config.features().size() == 2 : config.features();
        FeatureConfig base = config.features().getFirst();
        assert base.definitionType() == null : base.definitionType();
        assert base.valueShape().equals("SCALAR") : base.valueShape();
        assert base.additionalProperties().get("future_business_field").equals("kept");
        assert config.features().get(1).expression().equals("multiply(price, price)");
    }

    private static void testLegacyDerivedFeaturesRejected() {
        IllegalArgumentException error = expectThrows(
                IllegalArgumentException.class,
                () -> FeatureConfigLoader.load("""
                        {"features":[],"derivedFeatures":[],
                         "feature_set_name":"legacy","version":"latest"}
                        """));
        assert error.getMessage().contains("derivedFeatures") : error.getMessage();
    }

    private static void testIntermediateFeatureMapping() {
        FeatureSetConfig config = FeatureConfigLoader.load(intermediateConfigJson());
        MappedFeatureSet mapped = FeatureConfigMapper.map(
                config,
                ExecutionEnvironment.OFFLINE,
                Set.of(),
                Map.of());

        assert mapped.targetFeatures().equals(new LinkedHashSet<>(List.of("price_score")))
                : mapped.targetFeatures();
        assert mapped.outputs().size() == 1 : mapped.outputs();
        assert mapped.outputs().getFirst().storeName().equals("price_score_out");
        assert mapped.definitions().stream().anyMatch(definition ->
                definition.name().equals("normalized_price")
                        && definition.outputPolicy() == OutputPolicy.INTERNAL_ONLY)
                : "Intermediate definition must remain available to the dependency closure";

        LogicalDag dag = new LogicalDagBuilder(
                new ExpressionParser(), OperatorRegistry.standard())
                .build(mapped.definitions(), mapped.targetFeatures());
        assert dag.featureOutputNodeIds().containsKey("normalized_price")
                : "Final target must recursively build its intermediate dependency";
        assert !dag.rootNodeIds().contains(dag.featureOutputNodeIds().get("normalized_price"))
                : "Intermediate dependency must not become an output root";
    }

    private static String intermediateConfigJson() {
        return """
                {
                  "features": [
                    {
                      "name": "price",
                      "raw_name": "raw_price",
                      "store_name": "price",
                      "type": "DOUBLE",
                      "dft": 0.0,
                      "to_use": true,
                      "order": 1
                    },
                    {
                      "name": "quality_score",
                      "raw_name": "quality_score",
                      "store_name": "quality_score",
                      "type": "DOUBLE",
                      "dft": 0.0,
                      "to_use": true,
                      "order": 2
                    },
                    {
                      "name": "normalized_price",
                      "store_name": "normalized_price",
                      "type": "DOUBLE",
                      "definition_type": "DERIVED",
                      "expression": "normalize(price, {\\\"method\\\":\\\"min_max\\\",\\\"min\\\":0,\\\"max\\\":1000})",
                      "to_use": true,
                      "output_policy": "INTERNAL_ONLY",
                      "order": 1000
                    },
                    {
                      "name": "price_score",
                      "store_name": "price_score_out",
                      "type": "DOUBLE",
                      "definition_type": "DERIVED",
                      "expression": "multiply(normalized_price, quality_score)",
                      "to_use": true,
                      "output_policy": "OUTPUT",
                      "order": 1001
                    }
                  ],
                  "feature_set_name": "test_001",
                  "version": "latest"
                }
                """;
    }

    private static void testOfflinePublicApi() {
        FeatureDagEngine engine = FeatureDagEngine.init(
                intermediateConfigJson(),
                InitOptions.offline("offline-public-api"));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("raw_price", 100.0);
        row.put("quality_score", 0.8);
        GenerateResult result = engine.generate(
                new OfflineGenerateRequest("row-1", row));

        assert result.executionId().equals("row-1");
        assert result.featureValues().keySet().equals(Set.of("price_score_out"))
                : result.featureValues();
        assert Math.abs(((Number) result.featureValues().get("price_score_out")).doubleValue() - 0.08)
                < 0.000001 : result.featureValues();
        assert result.candidateFeatureValues().isEmpty();
        assert !result.featureValues().containsKey("normalized_price")
                : "Internal feature leaked through the public boundary";
    }

    private static void testConfigPathInit() throws Exception {
        Path configPath = Files.createTempFile("feature-dag-config-", ".json");
        try {
            Files.writeString(configPath, intermediateConfigJson(), StandardCharsets.UTF_8);
            FeatureDagEngine engine = FeatureDagEngine.init(
                    configPath,
                    InitOptions.offline("offline-path-api"));
            GenerateResult result = engine.generate(new OfflineGenerateRequest(
                    "row-path",
                    Map.of("raw_price", 100.0, "quality_score", 0.8)));
            assert Math.abs(((Number) result.featureValues().get("price_score_out")).doubleValue() - 0.08)
                    < 0.000001 : result.featureValues();
        } finally {
            Files.deleteIfExists(configPath);
        }
    }

    private static void testOnlinePublicApi() {
        FeatureDagEngine engine = FeatureDagEngine.init(
                onlineConfigJson(),
                InitOptions.online("online-public-api"));
        GenerateResult result = engine.generate(new OnlineGenerateRequest(
                "request-1",
                Map.of("user_click_count", 10, "user_seq1", sequence()),
                List.of(
                        Map.of("item_industry", "industry1", "item_price", 100.0),
                        Map.of("item_industry", "industry2", "item_price", 50.0),
                        Map.of("item_industry", "industry1", "item_price", 80.0))));

        assert result.featureValues().isEmpty() : result.featureValues();
        assert result.candidateFeatureValues().size() == 3;
        assert result.candidateFeatureValues().stream()
                .map(values -> values.get("same_industry_count"))
                .toList().equals(List.of(3, 1, 3)) : result.candidateFeatureValues();
        assert result.candidateFeatureValues().stream()
                .allMatch(values -> values.containsKey("final_score"));
        assert List.copyOf(result.candidateFeatureValues().getFirst().keySet())
                .equals(List.of("same_industry_count", "final_score"))
                : result.candidateFeatureValues().getFirst();
    }

    private static void testOnlineEngineConcurrentReuse() throws Exception {
        FeatureDagEngine engine = FeatureDagEngine.init(
                onlineConfigJson(), InitOptions.online("online-concurrent"));
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            List<Callable<List<Object>>> calls = IntStream.range(0, 20)
                    .mapToObj(index -> (Callable<List<Object>>) () -> {
                        GenerateResult result = engine.generate(new OnlineGenerateRequest(
                                "request-" + index,
                                Map.of("user_click_count", 10, "user_seq1", sequence()),
                                List.of(
                                        Map.of("item_industry", "industry1", "item_price", 100.0),
                                        Map.of("item_industry", "industry2", "item_price", 50.0))));
                        return result.candidateFeatureValues().stream()
                                .map(values -> values.get("same_industry_count"))
                                .toList();
                    })
                    .toList();
            for (Future<List<Object>> future : executor.invokeAll(calls)) {
                assert future.get().equals(List.of(3, 1)) : future.get();
            }
        }
    }

    private static String onlineConfigJson() {
        return """
                {
                  "features": [
                    {"name":"user_click_count","raw_name":"user_click_count","type":"INT","dft":0,"entity_scopes":["USER"]},
                    {"name":"user_seq1","raw_name":"user_seq1","type":"EVENT_SEQUENCE","entity_scopes":["USER"]},
                    {"name":"item_industry","raw_name":"item_industry","type":"STRING","dft":"unknown","entity_scopes":["ITEM"]},
                    {"name":"item_price","raw_name":"item_price","type":"DOUBLE","dft":0.0,"entity_scopes":["ITEM"]},
                    {
                      "name":"user_click_score",
                      "type":"DOUBLE",
                      "definition_type":"DERIVED",
                      "expression":"normalize(coalesce(user_click_count, 0), {\\\"method\\\":\\\"min_max\\\",\\\"min\\\":0,\\\"max\\\":100})",
                      "output_policy":"INTERNAL_ONLY"
                    },
                    {
                      "name":"same_industry_seq",
                      "type":"EVENT_SEQUENCE",
                      "definition_type":"DERIVED",
                      "expression":"extractIndustry(user_seq1, item_industry)",
                      "output_policy":"INTERNAL_ONLY"
                    },
                    {
                      "name":"same_industry_count",
                      "store_name":"same_industry_count",
                      "type":"INT",
                      "definition_type":"DERIVED",
                      "expression":"count(same_industry_seq)",
                      "output_policy":"OUTPUT",
                      "order":1
                    },
                    {
                      "name":"item_price_log",
                      "type":"DOUBLE",
                      "definition_type":"DERIVED",
                      "expression":"log(add(item_price, 1))",
                      "output_policy":"INTERNAL_ONLY"
                    },
                    {
                      "name":"final_score",
                      "store_name":"final_score",
                      "type":"DOUBLE",
                      "definition_type":"DERIVED",
                      "expression":"multiply(user_click_score, item_price_log)",
                      "output_policy":"OUTPUT",
                      "order":2
                    }
                  ],
                  "feature_set_name":"online_features",
                  "version":"latest"
                }
                """;
    }

    private static void testOfflineSequenceMaterialization() {
        String json = """
                {
                  "features": [
                    {"name":"user_seq1","raw_name":"user_seq1","type":"EVENT_SEQUENCE"},
                    {"name":"item_industry","raw_name":"item_industry","type":"STRING"}, {
                    "name":"same_industry_seq",
                    "store_name":"same_industry_events",
                    "type":"EVENT_SEQUENCE",
                    "definition_type":"DERIVED",
                    "expression":"extractIndustry(user_seq1, item_industry)",
                    "output_policy":"OUTPUT"
                  }],
                  "feature_set_name":"sequence_output",
                  "version":"1"
                }
                """;
        FeatureDagEngine engine = FeatureDagEngine.init(json, InitOptions.offline("sequence-output"));
        GenerateResult result = engine.generate(new OfflineGenerateRequest(
                "sequence-row",
                Map.of("user_seq1", sequence(), "item_industry", "industry1")));
        Object output = result.featureValues().get("same_industry_events");
        assert output instanceof List<?> : output;
        List<?> events = (List<?>) output;
        assert events.size() == 3 : events;
        assert events.getFirst() instanceof Map<?, ?> : events.getFirst();
        assert ((Map<?, ?>) events.getFirst()).get("itemId").equals("h1") : events.getFirst();
    }

    private static void testConfigurationAndRequestValidation() {
        FeatureDagInitializationException invalidJson = expectThrows(
                FeatureDagInitializationException.class,
                () -> FeatureDagEngine.init("{not-json}", InitOptions.offline("invalid-json")));
        assert invalidJson.getMessage().contains("Invalid feature config JSON")
                : invalidJson.getMessage();

        FeatureDagInitializationException duplicateName = expectThrows(
                FeatureDagInitializationException.class,
                () -> FeatureDagEngine.init(
                        intermediateConfigJson().replace(
                                "\"name\": \"quality_score\"", "\"name\": \"price\""),
                        InitOptions.offline("duplicate-name")));
        assert duplicateName.getMessage().contains("price") : duplicateName.getMessage();

        String duplicateStoreJson = intermediateConfigJson()
                .replace("\"store_name\": \"normalized_price\"",
                        "\"store_name\": \"price_score_out\"")
                .replaceFirst("\"output_policy\": \"INTERNAL_ONLY\"",
                        "\"output_policy\": \"OUTPUT\"");
        FeatureDagInitializationException duplicateStore = expectThrows(
                FeatureDagInitializationException.class,
                () -> FeatureDagEngine.init(duplicateStoreJson, InitOptions.offline("duplicate-store")));
        assert duplicateStore.getMessage().contains("price_score_out") : duplicateStore.getMessage();

        InitOptions internalTargetOptions = InitOptions.builder()
                .environment(ExecutionEnvironment.OFFLINE)
                .planId("internal-target")
                .targetFeatures(Set.of("normalized_price"))
                .build();
        FeatureDagInitializationException internalTarget = expectThrows(
                FeatureDagInitializationException.class,
                () -> FeatureDagEngine.init(intermediateConfigJson(), internalTargetOptions));
        assert internalTarget.getMessage().contains("normalized_price") : internalTarget.getMessage();

        String disabledDependencyJson = intermediateConfigJson().replaceFirst(
                "\"to_use\": true,\\R\\s+\"output_policy\": \"INTERNAL_ONLY\"",
                "\"to_use\": false,\n                      \"output_policy\": \"INTERNAL_ONLY\"");
        FeatureDagInitializationException disabledDependency = expectThrows(
                FeatureDagInitializationException.class,
                () -> FeatureDagEngine.init(
                        disabledDependencyJson, InitOptions.offline("disabled-dependency")));
        assert disabledDependency.getMessage().contains("normalized_price")
                && disabledDependency.getMessage().contains("is disabled")
                : disabledDependency.getMessage();

        String cycleJson = intermediateConfigJson().replace(
                "normalize(price, {\\\"method\\\":\\\"min_max\\\",\\\"min\\\":0,\\\"max\\\":1000})",
                "multiply(price_score, quality_score)");
        FeatureDagInitializationException cycle = expectThrows(
                FeatureDagInitializationException.class,
                () -> FeatureDagEngine.init(cycleJson, InitOptions.offline("cycle")));
        assert cycle.getMessage().toLowerCase().contains("cycle")
                && cycle.getMessage().contains("normalized_price")
                && cycle.getMessage().contains("price_score") : cycle.getMessage();

        String missingScopeJson = onlineConfigJson().replace(
                "\"name\":\"item_price\",\"raw_name\":\"item_price\",\"type\":\"DOUBLE\",\"dft\":0.0,\"entity_scopes\":[\"ITEM\"]",
                "\"name\":\"item_price\",\"raw_name\":\"item_price\",\"type\":\"DOUBLE\",\"dft\":0.0");
        FeatureDagInitializationException missingScope = expectThrows(
                FeatureDagInitializationException.class,
                () -> FeatureDagEngine.init(missingScopeJson, InitOptions.online("missing-scope")));
        assert missingScope.getMessage().contains("item_price") : missingScope.getMessage();

        InitOptions scopeOverride = InitOptions.builder()
                .environment(ExecutionEnvironment.ONLINE)
                .planId("scope-override")
                .rawFeatureScopes(Map.of("item_price", Set.of(com.example.featuredag.definition.EntityScope.ITEM)))
                .build();
        FeatureDagEngine.init(missingScopeJson, scopeOverride);

        FeatureDagEngine offline = FeatureDagEngine.init(
                intermediateConfigJson(), InitOptions.offline("mode-mismatch"));
        FeatureGenerationException modeMismatch = expectThrows(
                FeatureGenerationException.class,
                () -> offline.generate(new OnlineGenerateRequest(
                        "wrong-request",
                        Map.of(),
                        List.of(Map.of("raw_price", 1.0, "quality_score", 1.0)))));
        assert modeMismatch.planId().equals("mode-mismatch") : modeMismatch.planId();
        assert modeMismatch.executionId().equals("wrong-request") : modeMismatch.executionId();

        String requiredPriceJson = intermediateConfigJson().replaceFirst(
                "\"dft\": 0.0", "\"dft\": null");
        FeatureDagEngine requiredPrice = FeatureDagEngine.init(
                requiredPriceJson, InitOptions.offline("required-price"));
        FeatureGenerationException missingInput = expectThrows(
                FeatureGenerationException.class,
                () -> requiredPrice.generate(new OfflineGenerateRequest(
                        "missing-input", Map.of("quality_score", 0.8))));
        assert missingInput.getMessage().contains("raw_price") : missingInput.getMessage();

        Map<String, Object> explicitNullRow = new LinkedHashMap<>();
        explicitNullRow.put("raw_price", null);
        explicitNullRow.put("quality_score", 0.8);
        FeatureGenerationException explicitNull = expectThrows(
                FeatureGenerationException.class,
                () -> requiredPrice.generate(new OfflineGenerateRequest(
                        "explicit-null", explicitNullRow)));
        assert !explicitNull.getMessage().contains("Missing source feature")
                : explicitNull.getMessage();
    }

    private static void testDeclaredScopeIsValidatedBeforeOverride() {
        String invalidDeclaredScopeJson = onlineConfigJson().replace(
                "\"name\":\"item_price\",\"raw_name\":\"item_price\",\"type\":\"DOUBLE\",\"dft\":0.0,\"entity_scopes\":[\"ITEM\"]",
                "\"name\":\"item_price\",\"raw_name\":\"item_price\",\"type\":\"DOUBLE\",\"dft\":0.0,\"entity_scopes\":[\"OTHER\"]");
        InitOptions override = InitOptions.builder()
                .environment(ExecutionEnvironment.ONLINE)
                .planId("invalid-declared-scope")
                .rawFeatureScopes(Map.of("item_price", Set.of(EntityScope.ITEM)))
                .build();

        FeatureDagInitializationException error = expectThrows(
                FeatureDagInitializationException.class,
                () -> FeatureDagEngine.init(invalidDeclaredScopeJson, override));
        assert error.getMessage().contains("OTHER") : error.getMessage();
    }

    private static void testBaseOutputPolicyIsValidated() {
        String invalidOutputPolicyJson = intermediateConfigJson().replaceFirst(
                "\"order\": 1", "\"order\": 1,\n                      \"output_policy\": \"BAD\"");

        FeatureDagInitializationException error = expectThrows(
                FeatureDagInitializationException.class,
                () -> FeatureDagEngine.init(invalidOutputPolicyJson, InitOptions.offline("invalid-base-policy")));
        assert error.getMessage().contains("output_policy") : error.getMessage();
    }

    private static void testDeclaredValueShapeAndScopeSemantics() {
        LogicalDag dag = buildDag(configWithDerivedShapeAndScopes("VECTOR", "[\"USER\", \"ITEM\"]"));
        assert dag.node(dag.featureOutputNodeIds().get("candidate_score")).valueShape()
                == ValueShape.CANDIDATE_VECTOR;
        assert dag.node("source:user_score").valueShape() == ValueShape.SCALAR;
        assert dag.node("source:user_history").valueShape() == ValueShape.SEQUENCE;

        LogicalDag inferredBaseShapes = buildDag(configWithoutBaseValueShapes());
        assert inferredBaseShapes.node("source:user_score").valueShape() == ValueShape.SCALAR;
        assert inferredBaseShapes.node("source:user_history").valueShape() == ValueShape.SEQUENCE;

        DagBuildException shapeError = expectThrows(DagBuildException.class,
                () -> buildDag(configWithDerivedShapeAndScopes("SCALAR", "[\"USER\", \"ITEM\"]")));
        assert shapeError.getMessage().contains(
                "Declared value shape mismatch for feature candidate_score") : shapeError.getMessage();

        DagBuildException scopeError = expectThrows(DagBuildException.class,
                () -> buildDag(configWithDerivedShapeAndScopes("VECTOR", "[\"USER\"]")));
        assert scopeError.getMessage().contains(
                "Declared entity scopes mismatch for feature candidate_score") : scopeError.getMessage();

        IllegalArgumentException enumError = expectThrows(IllegalArgumentException.class,
                () -> FeatureConfigMapper.map(
                        FeatureConfigLoader.load(configWithValueShape("MATRIX")),
                        ExecutionEnvironment.ONLINE,
                        Set.of(),
                        Map.of()));
        assert enumError.getMessage().contains("Invalid value_shape for feature") : enumError.getMessage();
    }

    private static LogicalDag buildDag(String json) {
        MappedFeatureSet mapped = FeatureConfigMapper.map(
                FeatureConfigLoader.load(json),
                ExecutionEnvironment.ONLINE,
                Set.of(),
                Map.of());
        return new LogicalDagBuilder(new ExpressionParser(), OperatorRegistry.standard())
                .build(mapped.definitions(), mapped.targetFeatures());
    }

    private static String configWithDerivedShapeAndScopes(String valueShape, String scopesJson) {
        return """
                {
                  "features": [
                    {"name":"user_score","raw_name":"user_score","type":"INT",
                     "entity_scopes":["USER"],"value_shape":"SCALAR"},
                    {"name":"user_history","raw_name":"user_history","type":"EVENT_SEQUENCE",
                     "entity_scopes":["USER"],"value_shape":"SEQUENCE"},
                    {"name":"item_score","raw_name":"item_score","type":"DOUBLE",
                     "entity_scopes":["ITEM"],"value_shape":"VECTOR"},
                    {"name":"candidate_score","type":"DOUBLE","definition_type":"DERIVED",
                     "expression":"coalesce(item_score, user_score, 0)","output_policy":"OUTPUT",
                     "entity_scopes":%s,"value_shape":"%s"},
                    {"name":"history_output","type":"EVENT_SEQUENCE","definition_type":"DERIVED",
                     "expression":"coalesce(user_history, user_history)","output_policy":"OUTPUT",
                     "entity_scopes":["USER"],"value_shape":"SEQUENCE"}
                  ],
                  "feature_set_name":"declared_shape","version":"1"
                }
                """.formatted(scopesJson, valueShape);
    }

    private static String configWithoutBaseValueShapes() {
        return configWithDerivedShapeAndScopes("VECTOR", "[\"USER\", \"ITEM\"]")
                .replaceFirst(",\"value_shape\":\"SCALAR\"", "")
                .replaceFirst(",\"value_shape\":\"SEQUENCE\"", "");
    }

    private static String configWithValueShape(String valueShape) {
        return configWithDerivedShapeAndScopes(valueShape, "[\"USER\", \"ITEM\"]");
    }

    private static void testDagBusinessSemantics() {
        LogicalDagBuilder builder = new LogicalDagBuilder(
                new ExpressionParser(), OperatorRegistry.standard());
        List<FeatureDefinition> definitions = ExampleFeatures.definitions();

        LinkedHashSet<String> onlineTargets = linkedSet("same_industry_count", "final_score");
        LogicalDag online = builder.build(definitions, onlineTargets);
        assert online.featureOutputNodeIds().keySet().containsAll(Set.of(
                "same_industry_count", "same_industry_seq", "user_seq1", "item_industry",
                "final_score", "user_click_score", "user_click_count", "item_price_log", "item_price"))
                : online.featureOutputNodeIds();
        assert online.featureOutput("user_click_score").entityScopes().equals(Set.of(EntityScope.USER));
        assert online.featureOutput("item_price_log").entityScopes().equals(Set.of(EntityScope.ITEM));
        assert online.featureOutput("same_industry_count").entityScopes()
                .equals(Set.of(EntityScope.USER, EntityScope.ITEM));
        assert online.featureOutput("same_industry_seq").valueShape() == ValueShape.SEQUENCE;
        assert online.featureOutput("same_industry_count").valueShape() == ValueShape.SCALAR;

        LinkedHashSet<String> offlineTargets = linkedSet(
                "same_industry_count", "final_score", "user_click_score",
                "same_industry_seq", "item_price_log");
        LogicalDag offline = builder.build(definitions, offlineTargets);
        assert offline.nodes().keySet().containsAll(online.nodes().keySet());
        assert offline.nodes().keySet().stream()
                .filter("feature:user_click_score"::equals)
                .count() == 1 : "Common dependency must be represented by one feature output node";

        DagBuildException unknownFeature = expectThrows(
                DagBuildException.class,
                () -> builder.build(definitions, Set.of("not_defined")));
        assert unknownFeature.getMessage().contains("not_defined") : unknownFeature.getMessage();

        DagBuildException syntax = expectThrows(
                DagBuildException.class,
                () -> builder.build(withDerived(
                        definitions, "bad_syntax", DataType.INT, "count(user_seq1"), Set.of("bad_syntax")));
        assert syntax.getMessage().contains("bad_syntax") : syntax.getMessage();

        DagBuildException unknownOperator = expectThrows(
                DagBuildException.class,
                () -> builder.build(withDerived(
                        definitions, "bad_operator", DataType.INT,
                        "notRegistered(user_click_count)"), Set.of("bad_operator")));
        assert unknownOperator.getMessage().contains("notRegistered") : unknownOperator.getMessage();

        DagBuildException arity = expectThrows(
                DagBuildException.class,
                () -> builder.build(withDerived(
                        definitions, "bad_arity", DataType.DOUBLE,
                        "normalize(user_click_count)"), Set.of("bad_arity")));
        assert arity.getMessage().contains("normalize") : arity.getMessage();

        List<FeatureDefinition> cyclic = new ArrayList<>(definitions);
        cyclic.add(FeatureDefinition.derived(
                "cycle_a", DataType.DOUBLE, "add(cycle_b, 1)", OutputPolicy.INTERNAL_ONLY));
        cyclic.add(FeatureDefinition.derived(
                "cycle_b", DataType.DOUBLE, "add(cycle_a, 1)", OutputPolicy.OUTPUT));
        DagBuildException cycle = expectThrows(
                DagBuildException.class,
                () -> builder.build(cyclic, Set.of("cycle_b")));
        assert cycle.getMessage().contains("cycle_a") && cycle.getMessage().contains("cycle_b")
                : cycle.getMessage();

        DagBuildException countScalar = expectThrows(
                DagBuildException.class,
                () -> builder.build(withDerived(
                        definitions, "bad_count", DataType.INT,
                        "count(item_price)"), Set.of("bad_count")));
        assert countScalar.getMessage().contains("count")
                && countScalar.getMessage().contains("item_price") : countScalar.getMessage();
    }

    private static void testExecutionStagesAndTargetSelection() {
        OperatorRegistry operators = OperatorRegistry.standard();
        LogicalDagBuilder builder = new LogicalDagBuilder(new ExpressionParser(), operators);
        PhysicalPlanner planner = new PhysicalPlanner();
        LogicalDagOptimizer optimizer = new LogicalDagOptimizer();

        LogicalDag onlineDag = builder.build(
                ExampleFeatures.definitions(), linkedSet("same_industry_count", "final_score"));
        PhysicalPlan plan = planner.plan(
                optimizer.analyze(onlineDag), ExecutionEnvironment.ONLINE, "stage-test");
        assert stageFor(plan, "source:user_click_count") == ExecutionStage.REQUEST_SHARED;
        assert stageFor(plan, "source:item_price") == ExecutionStage.CANDIDATE_BATCH;
        assert stageFor(plan, "feature:user_click_score") == ExecutionStage.REQUEST_SHARED;
        assert stageFor(plan, "feature:item_price_log") == ExecutionStage.CANDIDATE_BATCH;
        assert stageFor(plan, "feature:final_score") == ExecutionStage.CANDIDATE_BATCH;

        List<FeatureDefinition> sceneDefinitions = new ArrayList<>(ExampleFeatures.definitions());
        sceneDefinitions.add(FeatureDefinition.raw(
                "scene_hour", DataType.INT, EntityScope.SCENE, 0));
        sceneDefinitions.add(FeatureDefinition.derived(
                "scene_score", DataType.DOUBLE,
                "add(scene_hour, 1)", OutputPolicy.OUTPUT));
        sceneDefinitions.add(FeatureDefinition.derived(
                "user_scene_score", DataType.DOUBLE,
                "add(user_click_count, scene_hour)", OutputPolicy.OUTPUT));
        LogicalDag sceneDag = builder.build(
                sceneDefinitions, linkedSet("scene_score", "user_scene_score"));
        PhysicalPlan scenePlan = planner.plan(
                optimizer.analyze(sceneDag), ExecutionEnvironment.ONLINE, "scene-stage-test");
        assert sceneDag.featureOutput("scene_score").entityScopes().equals(Set.of(EntityScope.SCENE));
        assert sceneDag.featureOutput("user_scene_score").entityScopes()
                .equals(Set.of(EntityScope.USER, EntityScope.SCENE));
        assert stageFor(scenePlan, "feature:scene_score") == ExecutionStage.REQUEST_SHARED;
        assert stageFor(scenePlan, "feature:user_scene_score") == ExecutionStage.REQUEST_SHARED;
    }

    private static void testCandidateCardinalityAndDefaults() {
        FeatureDagEngine engine = FeatureDagEngine.init(
                onlineConfigJson(), InitOptions.online("candidate-cardinality"));
        Map<String, Object> shared = Map.of(
                "user_click_count", 12,
                "user_seq1", sequence());

        GenerateResult empty = engine.generate(new OnlineGenerateRequest(
                "zero-candidates", shared, List.of()));
        assert empty.candidateFeatureValues().isEmpty() : empty.candidateFeatureValues();

        GenerateResult single = engine.generate(new OnlineGenerateRequest(
                "one-candidate",
                shared,
                List.of(Map.of("item_industry", "industry1", "item_price", 20.0))));
        assert single.candidateFeatureValues().size() == 1;
        assert single.candidateFeatureValues().getFirst().get("same_industry_count").equals(3)
                : single.candidateFeatureValues();

        GenerateResult four = engine.generate(new OnlineGenerateRequest(
                "four-candidates", shared, fourCandidates()));
        assert four.candidateFeatureValues().stream()
                .map(values -> values.get("same_industry_count"))
                .toList().equals(List.of(3, 1, 3, 0)) : four.candidateFeatureValues();

        GenerateResult defaultPrice = engine.generate(new OnlineGenerateRequest(
                "default-price",
                shared,
                List.of(Map.of("item_industry", "industry2"))));
        assert ((Number) defaultPrice.candidateFeatureValues().getFirst().get("final_score"))
                .doubleValue() == 0.0 : defaultPrice.candidateFeatureValues();

        String requiredPriceConfig = onlineConfigJson().replace(
                "\"name\":\"item_price\",\"raw_name\":\"item_price\",\"type\":\"DOUBLE\",\"dft\":0.0,",
                "\"name\":\"item_price\",\"raw_name\":\"item_price\",\"type\":\"DOUBLE\",");
        FeatureDagEngine requiredPrice = FeatureDagEngine.init(
                requiredPriceConfig, InitOptions.online("required-candidate-price"));
        FeatureGenerationException missingPrice = expectThrows(
                FeatureGenerationException.class,
                () -> requiredPrice.generate(new OnlineGenerateRequest(
                        "missing-price",
                        shared,
                        List.of(Map.of("item_industry", "industry1")))));
        assert missingPrice.getMessage().contains("item_price") : missingPrice.getMessage();
    }

    private static void testEmptySequenceAndOfflineOutputSet() {
        FeatureDagEngine online = FeatureDagEngine.init(
                onlineConfigJson(), InitOptions.online("empty-sequence"));
        SequenceBlock emptySequence = new SequenceBlock("empty", 1L, List.of());
        GenerateResult empty = online.generate(new OnlineGenerateRequest(
                "empty-sequence-request",
                Map.of("user_click_count", 12, "user_seq1", emptySequence),
                List.of(Map.of("item_industry", "industry1", "item_price", 20.0))));
        assert empty.candidateFeatureValues().getFirst().get("same_industry_count").equals(0)
                : empty.candidateFeatureValues();

        FeatureDagEngine offline = FeatureDagEngine.init(
                onlineConfigJson(), InitOptions.offline("offline-output-set"));
        GenerateResult result = offline.generate(new OfflineGenerateRequest(
                "offline-output-row",
                Map.of(
                        "user_click_count", 12,
                        "user_seq1", sequence(),
                        "item_industry", "industry1",
                        "item_price", 20.0)));
        assert result.featureValues().keySet().equals(
                linkedSet("same_industry_count", "final_score")) : result.featureValues();
        assert !result.featureValues().containsKey("same_industry_seq");
        assert !result.featureValues().containsKey("user_click_score");
        assert !result.featureValues().containsKey("item_price_log");
    }

    private static ExecutionStage stageFor(PhysicalPlan plan, String logicalNodeId) {
        return plan.nodes().stream()
                .filter(node -> node.logicalNodeIds().contains(logicalNodeId))
                .findFirst()
                .map(PhysicalNode::executionStage)
                .orElseThrow(() -> new AssertionError(
                        "Logical node is absent from physical plan: " + logicalNodeId));
    }

    private static List<Map<String, Object>> fourCandidates() {
        return List.of(
                Map.of("item_id", "item1", "item_industry", "industry1", "item_price", 20.0),
                Map.of("item_id", "item2", "item_industry", "industry2", "item_price", 30.0),
                Map.of("item_id", "item3", "item_industry", "industry1", "item_price", 40.0),
                Map.of("item_id", "item4", "item_industry", "industry4", "item_price", 50.0));
    }

    private static void testCandidateDeduplicationAndFusion() {
        OperatorRegistry operators = OperatorRegistry.standard();
        LogicalDagBuilder builder = new LogicalDagBuilder(new ExpressionParser(), operators);
        LogicalDagOptimizer optimizer = new LogicalDagOptimizer();
        PhysicalPlanner planner = new PhysicalPlanner();
        DagRuntime runtime = new DagRuntime(operators);

        LogicalDag countDag = builder.build(
                ExampleFeatures.definitions(), Set.of("same_industry_count"));
        PhysicalPlan onlinePlan = planner.plan(
                optimizer.analyze(countDag), ExecutionEnvironment.ONLINE, "dedup-four");
        assert onlinePlan.nodes().stream()
                .anyMatch(node -> node.executorType() == ExecutorType.COUNT_INDUSTRY_BATCH)
                : "Online count plan should fuse extraction and count";

        ExecutionResult result = runtime.execute(
                onlinePlan,
                ExecutionContext.onlineRequest(
                        "dedup-four-request",
                        Map.of("user_seq1", sequence()),
                        fourCandidates()));
        CandidateVectorValue counts = (CandidateVectorValue) result.feature("same_industry_count");
        assert counts.values().equals(List.of(3, 1, 3, 0)) : counts.values();
        assert result.nodeStates().values().stream()
                .anyMatch(state -> state.dedupInputCount() == 4 && state.uniqueInputCount() == 3)
                : "Expected candidate industry dedup 4 -> 3";

        List<Map<String, Object>> reordered = List.of(
                Map.of("item_id", "item3", "item_industry", "industry1", "item_price", 40.0),
                Map.of("item_id", "item2", "item_industry", "industry2", "item_price", 30.0),
                Map.of("item_id", "item1", "item_industry", "industry1", "item_price", 20.0));
        ExecutionResult reorderedResult = runtime.execute(
                onlinePlan,
                ExecutionContext.onlineRequest(
                        "dedup-reordered-request",
                        Map.of("user_seq1", sequence()),
                        reordered));
        CandidateVectorValue reorderedCounts =
                (CandidateVectorValue) reorderedResult.feature("same_industry_count");
        assert reorderedCounts.values().equals(List.of(3, 1, 3)) : reorderedCounts.values();

        LogicalDag transformDag = builder.build(
                ExampleFeatures.definitions(), linkedSet("same_industry_seq", "same_industry_count"));
        PhysicalPlan offlinePlan = planner.plan(
                optimizer.analyze(transformDag), ExecutionEnvironment.OFFLINE, "view-not-fused");
        assert offlinePlan.nodes().stream()
                .noneMatch(node -> node.executorType() == ExecutorType.COUNT_INDUSTRY_BATCH)
                : "An observable sequence output must prevent count fusion";
        ExecutionResult offline = runtime.execute(
                offlinePlan,
                ExecutionContext.offlineRow(
                        "view-not-fused-row",
                        Map.of("user_seq1", sequence(), "item_industry", "industry3")));
        SequenceView view = (SequenceView) offline.feature("same_industry_seq");
        assert view.size() == 2 : view.size();
        assert ((Number) offline.feature("same_industry_count").raw()).intValue() == 2;
        assert view.baseBlock().sequenceId().equals("seq-test") : view.baseBlock().sequenceId();
    }

    private static void testSequenceSelectionStrategies() {
        SequenceBlock block = sequence();
        SequenceView range = SequenceView.slice(block, 2, 5);
        assert range.selection() instanceof RangeSelection : range.selection();

        SequenceBlock sparseBlock = selectionBlock(Set.of(1, 8));
        SequenceView sparse = SequenceView.filterByIndustry(sparseBlock, "keep");
        assert sparse.selection() instanceof IndexSelection : sparse.selection();
        assert sparse.baseIndexAt(0) == 1 && sparse.baseIndexAt(1) == 8;

        SequenceBlock denseBlock = selectionBlock(Set.of(0, 2, 3, 5, 6, 8));
        SequenceView dense = SequenceView.filterByIndustry(denseBlock, "keep");
        assert dense.selection() instanceof BitmapSelection : dense.selection();
        assert dense.size() == 6 : dense.size();

        SequenceView chained = SequenceView.slice(
                SequenceView.filterByIndustry(block, "industry1"), 1, 3);
        assert chained.selection() instanceof IndexSelection : chained.selection();
        assert chained.baseBlock() == block : "View chains must retain the original block";
        assert chained.baseIndexAt(0) == 2 : chained.baseIndexAt(0);
        assert chained.baseIndexAt(1) == 4 : chained.baseIndexAt(1);
    }

    private static SequenceBlock selectionBlock(Set<Integer> keepIndices) {
        List<SequenceEvent> events = IntStream.range(0, 10)
                .mapToObj(index -> new SequenceEvent(
                        "selection-" + index,
                        keepIndices.contains(index) ? "keep" : "drop",
                        index,
                        "view",
                        1.0))
                .toList();
        return new SequenceBlock("selection-block", 1L, events);
    }

    private static void testOfflineOnlineConsistency() {
        FeatureDagEngine offline = FeatureDagEngine.init(
                onlineConfigJson(), InitOptions.offline("consistency-offline"));
        FeatureDagEngine online = FeatureDagEngine.init(
                onlineConfigJson(), InitOptions.online("consistency-online"));
        Map<String, Object> shared = Map.of(
                "user_click_count", 12,
                "user_seq1", sequence());

        Map<String, Map<String, Object>> offlineByItemId = new LinkedHashMap<>();
        for (Map<String, Object> candidate : fourCandidates()) {
            GenerateResult result = offline.generate(new OfflineGenerateRequest(
                    "offline-" + candidate.get("item_id"),
                    mergedRow(shared, candidate)));
            offlineByItemId.put(String.valueOf(candidate.get("item_id")), result.featureValues());
        }

        GenerateResult onlineBatch = online.generate(new OnlineGenerateRequest(
                "consistency-batch", shared, fourCandidates()));
        for (int index = 0; index < fourCandidates().size(); index++) {
            String itemId = String.valueOf(fourCandidates().get(index).get("item_id"));
            assertFeatureValuesEqual(
                    offlineByItemId.get(itemId),
                    onlineBatch.candidateFeatureValues().get(index));
        }

        List<Map<String, Object>> reordered = List.of(
                fourCandidates().get(2),
                fourCandidates().get(1),
                fourCandidates().get(0));
        GenerateResult reorderedBatch = online.generate(new OnlineGenerateRequest(
                "consistency-reordered", shared, reordered));
        for (int index = 0; index < reordered.size(); index++) {
            String itemId = String.valueOf(reordered.get(index).get("item_id"));
            assertFeatureValuesEqual(
                    offlineByItemId.get(itemId),
                    reorderedBatch.candidateFeatureValues().get(index));
        }

        Map<String, Object> missingUserShared = Map.of("user_seq1", sequence());
        Map<String, Object> firstCandidate = fourCandidates().getFirst();
        GenerateResult offlineDefault = offline.generate(new OfflineGenerateRequest(
                "consistency-default-offline",
                mergedRow(missingUserShared, firstCandidate)));
        GenerateResult onlineDefault = online.generate(new OnlineGenerateRequest(
                "consistency-default-online",
                missingUserShared,
                List.of(firstCandidate)));
        assertFeatureValuesEqual(
                offlineDefault.featureValues(),
                onlineDefault.candidateFeatureValues().getFirst());

        Map<String, Object> emptySequenceShared = Map.of(
                "user_click_count", 12,
                "user_seq1", new SequenceBlock("consistency-empty", 1L, List.of()));
        GenerateResult offlineEmpty = offline.generate(new OfflineGenerateRequest(
                "consistency-empty-offline",
                mergedRow(emptySequenceShared, firstCandidate)));
        GenerateResult onlineEmpty = online.generate(new OnlineGenerateRequest(
                "consistency-empty-online",
                emptySequenceShared,
                List.of(firstCandidate)));
        assertFeatureValuesEqual(
                offlineEmpty.featureValues(),
                onlineEmpty.candidateFeatureValues().getFirst());
        assert offlineEmpty.featureValues().get("same_industry_count").equals(0)
                : offlineEmpty.featureValues();
    }

    private static Map<String, Object> mergedRow(
            Map<String, Object> shared,
            Map<String, Object> candidate) {
        Map<String, Object> row = new LinkedHashMap<>(shared);
        candidate.forEach((key, value) -> {
            if (!key.equals("item_id")) row.put(key, value);
        });
        return row;
    }

    private static void assertFeatureValuesEqual(
            Map<String, Object> offline,
            Map<String, Object> online) {
        assert offline.get("same_industry_count").equals(online.get("same_industry_count"))
                : "offline=" + offline + ", online=" + online;
        double offlineScore = ((Number) offline.get("final_score")).doubleValue();
        double onlineScore = ((Number) online.get("final_score")).doubleValue();
        assert Math.abs(offlineScore - onlineScore) <= 1e-9
                : "offline=" + offlineScore + ", online=" + onlineScore;
    }

    private static List<FeatureDefinition> withDerived(
            List<FeatureDefinition> definitions,
            String name,
            DataType type,
            String expression) {
        List<FeatureDefinition> result = new ArrayList<>(definitions);
        result.add(FeatureDefinition.derived(name, type, expression, OutputPolicy.OUTPUT));
        return List.copyOf(result);
    }

    private static LinkedHashSet<String> linkedSet(String... values) {
        return new LinkedHashSet<>(List.of(values));
    }

    private static <T extends Throwable> T expectThrows(Class<T> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) return type.cast(error);
            throw new AssertionError("Expected " + type.getName() + " but got " + error, error);
        }
        throw new AssertionError("Expected " + type.getName() + " but nothing was thrown");
    }

    private static SequenceBlock sequence() {
        return new SequenceBlock("seq-test", 1L, List.of(
                new SequenceEvent("h1", "industry1", 1L, "click", 1.0),
                new SequenceEvent("h2", "industry2", 2L, "click", 1.0),
                new SequenceEvent("h3", "industry1", 3L, "view", 1.0),
                new SequenceEvent("h4", "industry3", 4L, "view", 1.0),
                new SequenceEvent("h5", "industry1", 5L, "buy", 1.0),
                new SequenceEvent("h6", "industry3", 6L, "view", 1.0)));
    }
}
