package com.example.featuredag;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.GenerateResult;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OfflineBatchGenerateRequest;
import com.example.featuredag.api.OfflineBatchGenerateResult;
import com.example.featuredag.api.OfflineGenerateRequest;
import com.example.featuredag.config.FeatureConfig;
import com.example.featuredag.config.FeatureConfigLoader;
import com.example.featuredag.config.FeatureConfigMapper;
import com.example.featuredag.config.FeatureSetConfig;
import com.example.featuredag.config.MappedFeatureSet;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.FeatureDefinition;
import com.example.featuredag.definition.FeatureRole;
import com.example.featuredag.definition.OutputPolicy;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.logical.LogicalNode;
import com.example.featuredag.logical.NodeInput;
import com.example.featuredag.logical.OperatorNode;
import com.example.featuredag.logical.SourceNode;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.physical.ExecutionEnvironment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 基于模型特征集 JSON 的首批 8 算子配置、深层 DAG 和批执行自测试。 */
public final class ModelFeatureSetInitialOperatorsSelfTest {
    private static final String RESOURCE = "/model-feature-set-initial-operators.json";
    private static final int BASE_FEATURE_COUNT = 7;
    private static final Set<String> UNUSED_BASE_FEATURES = Set.of(
            "unused_campaign_id", "unused_request_hour", "unused_history_seq");
    private static final Set<String> INITIAL_OPERATOR_NAMES = Set.of(
            "discrete",
            "log_base",
            "slice_by_indices",
            "find_indices",
            "get_seq_length",
            "count_distinct",
            "zip_concat",
            "calc_delta_seq");

    private ModelFeatureSetInitialOperatorsSelfTest() {}

    public static void run() {
        String configJson = loadConfig();
        testBaseBlankFieldsAndDerivedDeclarations(configJson);
        testDeeplyNestedAllOperators(configJson);
        testOfflineBatchBoundaryCases(configJson);
    }

    private static void testBaseBlankFieldsAndDerivedDeclarations(String configJson) {
        FeatureSetConfig config = FeatureConfigLoader.load(configJson);
        assert config.features().size() == 17 : config.features().size();

        for (int index = 0; index < BASE_FEATURE_COUNT; index++) {
            FeatureConfig base = config.features().get(index);
            assert base.definitionType().isEmpty() : base.name();
            assert base.outputPolicy().isEmpty() : base.name();
            assert base.valueShape().isEmpty() : base.name();
            assert base.entityScopes().isEmpty() : base.name();
            assert base.expression().isEmpty() : base.name();
        }
        for (int index = BASE_FEATURE_COUNT; index < config.features().size(); index++) {
            FeatureConfig derived = config.features().get(index);
            assert "DERIVED".equals(derived.definitionType()) : derived.name();
            assert "OUTPUT".equals(derived.outputPolicy()) : derived.name();
            assert !derived.valueShape().isBlank() : derived.name();
            assert derived.entityScopes().equals(List.of("USER")) : derived.name();
            assert !derived.expression().isBlank() : derived.name();
        }
        assert "/context_feature".equals(
                config.features().get(0).additionalProperties().get("catalog"));
        assert Boolean.FALSE.equals(
                config.features().get(0).additionalProperties().get("isModuleInHash"));

        MappedFeatureSet mapped = FeatureConfigMapper.map(
                config,
                Set.of("deeply_nested_all_operators"),
                Map.of());
        Map<String, FeatureDefinition> definitions = definitionsByName(mapped.definitions());
        for (int index = 0; index < BASE_FEATURE_COUNT; index++) {
            FeatureDefinition base = definitions.get(config.features().get(index).name());
            assert base.role() == FeatureRole.RAW : base.name();
            assert base.entityScopes().equals(Set.of(EntityScope.USER)) : base.name();
            assert base.outputPolicy() == OutputPolicy.OUTPUT : base.name();
        }
        assert definitions.get("ad_type").declaredValueShape() == null;
        assert definitions.get("ad_type_seq").declaredValueShape() == ValueShape.SEQUENCE;
        assert definitions.get("label_seq").declaredValueShape() == ValueShape.SEQUENCE;
        assert definitions.get("score_seq").declaredValueShape() == ValueShape.SEQUENCE;
        assert definitions.get("unused_history_seq").declaredValueShape()
                == ValueShape.SEQUENCE;
    }

    private static void testDeeplyNestedAllOperators(String configJson) {
        FeatureSetConfig config = FeatureConfigLoader.load(configJson);
        MappedFeatureSet mapped = FeatureConfigMapper.map(
                config,
                Set.of("deeply_nested_all_operators"),
                Map.of());
        LogicalDag dag = new LogicalDagBuilder(
                new ExpressionParser(), OperatorRegistry.standard())
                .build(mapped.definitions(), mapped.targetFeatures());

        Set<String> operatorNames = new LinkedHashSet<String>();
        Set<String> sourceFeatureNames = new LinkedHashSet<String>();
        for (LogicalNode node : dag.nodes().values()) {
            if (node instanceof OperatorNode) {
                operatorNames.add(((OperatorNode) node).operatorName());
            } else if (node instanceof SourceNode) {
                sourceFeatureNames.add(((SourceNode) node).featureName());
            }
        }
        assert operatorNames.equals(INITIAL_OPERATOR_NAMES) : operatorNames;
        assert sourceFeatureNames.equals(
                Set.of("ad_type", "ad_type_seq", "label_seq", "score_seq"))
                : sourceFeatureNames;
        for (String unusedFeature : UNUSED_BASE_FEATURES) {
            assert !dag.featureOutputNodeIds().containsKey(unusedFeature) : unusedFeature;
        }
        String rootNodeId = dag.featureOutput("deeply_nested_all_operators")
                .inputs().get(0).nodeId();
        assert operatorDepth(dag, rootNodeId, new LinkedHashMap<String, Integer>()) >= 8;

        FeatureDagEngine engine = FeatureDagEngine.init(
                configJson,
                InitOptions.builder()
                        .environment(ExecutionEnvironment.OFFLINE)
                        .planId("model-feature-deep-chain-test")
                        .targetFeatures(allDerivedFeatureNames(config))
                        .build());
        GenerateResult result = engine.generate(new OfflineGenerateRequest(
                "model-feature-deep-chain-row", firstRow()));
        Map<String, List<?>> values = result.featureValues();

        assertFeature(values, "matching_indices", List.of(0, 2));
        assertFeature(values, "matching_ad_types", List.of("A", "A"));
        assertFeature(values, "matching_labels", List.of("x", "z"));
        assertFeature(values, "zipped_matching_context", List.of("A|x", "A|z"));
        assertFeature(values, "distinct_matching_context", List.of(2));
        assertFeature(values, "match_count_bucket", List.of(2));
        assertDoubleFeature(values, "match_count_log2", 1.0);
        assertDoubleSequence(values, "adjusted_scores", List.of(1.0, 4.0, 8.0));
        assertFeature(values, "adjusted_scores_length", List.of(3));
        assertFeature(values, "deeply_nested_all_operators", List.of(3));
    }

    private static void testOfflineBatchBoundaryCases(String configJson) {
        FeatureSetConfig config = FeatureConfigLoader.load(configJson);
        FeatureDagEngine engine = FeatureDagEngine.init(
                configJson,
                InitOptions.builder()
                        .environment(ExecutionEnvironment.OFFLINE)
                        .planId("model-feature-batch-boundary-test")
                        .targetFeatures(allDerivedFeatureNames(config))
                        .build());
        OfflineBatchGenerateResult result = engine.generateBatch(
                new OfflineBatchGenerateRequest(
                        "model-feature-batch-boundary-rows",
                        List.of(firstRow(), noMatchRow(), fourDistinctMatchesRow())));
        assert result.rows().size() == 3 : result.rows().size();

        Map<String, List<?>> noMatch = result.rows().get(1);
        assertFeature(noMatch, "matching_indices", List.of());
        assertFeature(noMatch, "zipped_matching_context", List.of());
        assertFeature(noMatch, "distinct_matching_context", List.of(0));
        assertFeature(noMatch, "match_count_bucket", List.of(1));
        assertDoubleFeature(noMatch, "match_count_log2", 0.0);
        assertDoubleSequence(noMatch, "adjusted_scores", List.of());
        assertFeature(noMatch, "adjusted_scores_length", List.of(0));
        assertFeature(noMatch, "deeply_nested_all_operators", List.of(0));

        Map<String, List<?>> fourDistinct = result.rows().get(2);
        double expectedLog = Math.log(3.0) / Math.log(2.0);
        assertFeature(fourDistinct, "matching_indices", List.of(0, 1, 2, 3));
        assertFeature(fourDistinct, "distinct_matching_context", List.of(4));
        assertFeature(fourDistinct, "match_count_bucket", List.of(3));
        assertDoubleFeature(fourDistinct, "match_count_log2", expectedLog);
        assertDoubleSequence(
                fourDistinct,
                "adjusted_scores",
                List.of(2.0 - expectedLog, 5.0 - expectedLog,
                        9.0 - expectedLog, 11.0 - expectedLog));
        assertFeature(fourDistinct, "adjusted_scores_length", List.of(4));
        assertFeature(fourDistinct, "deeply_nested_all_operators", List.of(4));
    }

    private static Map<String, FeatureDefinition> definitionsByName(
            List<FeatureDefinition> definitions) {
        Map<String, FeatureDefinition> result = new LinkedHashMap<String, FeatureDefinition>();
        for (FeatureDefinition definition : definitions) {
            result.put(definition.name(), definition);
        }
        return result;
    }

    private static Set<String> allDerivedFeatureNames(FeatureSetConfig config) {
        Set<String> result = new LinkedHashSet<String>();
        for (FeatureConfig feature : config.features()) {
            if ("DERIVED".equals(feature.definitionType())) result.add(feature.name());
        }
        return result;
    }

    private static int operatorDepth(
            LogicalDag dag,
            String nodeId,
            Map<String, Integer> memo) {
        Integer cached = memo.get(nodeId);
        if (cached != null) return cached;
        LogicalNode node = dag.node(nodeId);
        int dependencyDepth = 0;
        for (NodeInput input : node.inputs()) {
            dependencyDepth = Math.max(
                    dependencyDepth, operatorDepth(dag, input.nodeId(), memo));
        }
        int depth = dependencyDepth + (node instanceof OperatorNode ? 1 : 0);
        memo.put(nodeId, depth);
        return depth;
    }

    private static Map<String, List<?>> firstRow() {
        return row(
                "A",
                List.of("A", "B", "A", "D"),
                List.of("x", "y", "z", "w"),
                List.of(2.0, 5.0, 9.0));
    }

    private static Map<String, List<?>> noMatchRow() {
        return row(
                "Z",
                List.of("A", "B"),
                List.of("x", "y"),
                List.of());
    }

    private static Map<String, List<?>> fourDistinctMatchesRow() {
        return row(
                "A",
                List.of("A", "A", "A", "A"),
                List.of("w", "x", "y", "z"),
                List.of(2.0, 5.0, 9.0, 11.0));
    }

    private static Map<String, List<?>> row(
            String adType,
            List<String> adTypes,
            List<String> labels,
            List<Double> scores) {
        Map<String, List<?>> result = new LinkedHashMap<String, List<?>>();
        result.put("ad_type", List.of(adType));
        result.put("ad_type_seq", adTypes);
        result.put("label_seq", labels);
        result.put("score_seq", scores);
        return result;
    }

    private static void assertFeature(
            Map<String, List<?>> values,
            String featureName,
            List<?> expected) {
        List<?> actual = values.get(featureName);
        assert expected.equals(actual)
                : featureName + ": expected=" + expected + ", actual=" + actual;
    }

    private static void assertDoubleFeature(
            Map<String, List<?>> values,
            String featureName,
            double expected) {
        List<?> actual = values.get(featureName);
        assert actual != null && actual.size() == 1 : featureName + "=" + actual;
        double value = ((Number) actual.get(0)).doubleValue();
        assert Math.abs(value - expected) < 1e-9
                : featureName + ": expected=" + expected + ", actual=" + value;
    }

    private static void assertDoubleSequence(
            Map<String, List<?>> values,
            String featureName,
            List<Double> expected) {
        List<?> actual = values.get(featureName);
        assert actual != null && actual.size() == expected.size()
                : featureName + ": expected=" + expected + ", actual=" + actual;
        for (int index = 0; index < expected.size(); index++) {
            double value = ((Number) actual.get(index)).doubleValue();
            assert Math.abs(value - expected.get(index)) < 1e-9
                    : featureName + "[" + index + "]: expected="
                            + expected.get(index) + ", actual=" + value;
        }
    }

    private static String loadConfig() {
        InputStream stream = ModelFeatureSetInitialOperatorsSelfTest.class
                .getResourceAsStream(RESOURCE);
        if (stream == null) throw new AssertionError("Missing test resource: " + RESOURCE);
        try (InputStream input = stream) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new AssertionError("Failed to read test resource: " + RESOURCE, error);
        }
    }
}
