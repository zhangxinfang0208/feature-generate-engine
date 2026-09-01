package com.example.featuredag.api;

import com.example.featuredag.runtime.RuntimeNodeExecutionException;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 覆盖 Runtime Batch 算子失败到目标特征集合的规划期关联与公共异常传播。 */
public final class RuntimeFailureFeatureAssociationTest {

    @Test
    public void offlineBatchPreservesLegacyFeatureNameForUniqueTarget() {
        FeatureDagEngine engine = FeatureDagEngine.init(
                uniqueTargetConfig(), InitOptions.offline("runtime-failure-unique"));

        FeatureGenerationException failure = assertThrows(
                FeatureGenerationException.class,
                () -> engine.generateBatch(new OfflineBatchGenerateRequest(
                        "offline-unique",
                        List.of(scoreRow(4.0), scoreRow(0.0)))));

        assertEquals("score_log", failure.featureName());
        assertEquals(List.of("score_log"), failure.featureNames());
        assertTrue(failure.getMessage().contains("offline batch row 1"));
        assertTrue(failure.getCause() instanceof RuntimeNodeExecutionException);
        RuntimeNodeExecutionException nodeFailure =
                (RuntimeNodeExecutionException) failure.getCause();
        assertEquals(List.of("score_log"), nodeFailure.affectedFeatureNames());
        assertTrue(nodeFailure.physicalNodeId().contains(":operator"));
    }

    @Test
    public void offlineBatchReportsEveryTargetOfSharedOperator() {
        FeatureDagEngine engine = FeatureDagEngine.init(
                sharedTargetConfig(), InitOptions.offline("runtime-failure-shared"));

        FeatureGenerationException failure = assertThrows(
                FeatureGenerationException.class,
                () -> engine.generateBatch(new OfflineBatchGenerateRequest(
                        "offline-shared",
                        List.of(scoreRow(4.0), scoreRow(0.0)))));

        assertNull(failure.featureName());
        assertEquals(List.of("score_log_a", "score_log_b"), failure.featureNames());
        assertTrue(failure.getMessage().contains(
                "features=[score_log_a, score_log_b]"));
        assertTrue(failure.getMessage().contains("Operator log_base failed"));
        assertTrue(failure.getMessage().contains("offline batch row 1"));
        assertTrue(failure.getCause() instanceof RuntimeNodeExecutionException);
        RuntimeNodeExecutionException nodeFailure =
                (RuntimeNodeExecutionException) failure.getCause();
        assertEquals(failure.featureNames(), nodeFailure.affectedFeatureNames());
    }

    @Test
    public void onlineBatchKeepsGroupAndCandidateLocationWithAffectedTargets() {
        FeatureDagEngine engine = FeatureDagEngine.init(
                sharedTargetConfig(), InitOptions.online("runtime-failure-online"));
        OnlineBatchGenerateRequest request = new OnlineBatchGenerateRequest(
                "online-shared",
                List.of(
                        new OnlineRequestGroup(
                                "group-0", Map.of(), List.of(scoreRow(4.0))),
                        new OnlineRequestGroup(
                                "group-1",
                                Map.of(),
                                List.of(scoreRow(2.0), scoreRow(0.0)))));

        FeatureGenerationException failure = assertThrows(
                FeatureGenerationException.class,
                () -> engine.generateBatch(request));

        assertEquals(List.of("score_log_a", "score_log_b"), failure.featureNames());
        assertTrue(failure.getMessage().contains(
                "online batch group 1 (group-1), candidate 1"));
    }

    private static Map<String, List<?>> scoreRow(double score) {
        return Map.of("score", List.of(score));
    }

    private static String uniqueTargetConfig() {
        return """
                {
                  "feature_set_name": "runtime-failure-unique",
                  "version": "1",
                  "features": [
                    {
                      "name": "score",
                      "raw_name": "score",
                      "type": "DOUBLE",
                      "definition_type": "BASE",
                      "value_shape": "SCALAR",
                      "entity_scopes": ["ITEM"]
                    },
                    {
                      "name": "score_log",
                      "store_name": "score_log",
                      "type": "DOUBLE",
                      "definition_type": "DERIVED",
                      "value_shape": "SCALAR",
                      "expression": "log_base(score, 2, 16)",
                      "output_policy": "OUTPUT",
                      "entity_scopes": ["ITEM"]
                    }
                  ]
                }
                """;
    }

    private static String sharedTargetConfig() {
        return """
                {
                  "feature_set_name": "runtime-failure-shared",
                  "version": "1",
                  "features": [
                    {
                      "name": "score",
                      "raw_name": "score",
                      "type": "DOUBLE",
                      "definition_type": "BASE",
                      "value_shape": "SCALAR",
                      "entity_scopes": ["ITEM"]
                    },
                    {
                      "name": "score_log_a",
                      "store_name": "score_log_a",
                      "type": "DOUBLE",
                      "definition_type": "DERIVED",
                      "value_shape": "SCALAR",
                      "expression": "log_base(score, 2, 16)",
                      "output_policy": "OUTPUT",
                      "order": 1,
                      "entity_scopes": ["ITEM"]
                    },
                    {
                      "name": "score_log_b",
                      "store_name": "score_log_b",
                      "type": "DOUBLE",
                      "definition_type": "DERIVED",
                      "value_shape": "SCALAR",
                      "expression": "log_base(score, 2, 16)",
                      "output_policy": "OUTPUT",
                      "order": 2,
                      "entity_scopes": ["ITEM"]
                    },
                    {
                      "name": "score_plus_one",
                      "store_name": "score_plus_one",
                      "type": "DOUBLE",
                      "definition_type": "DERIVED",
                      "value_shape": "SCALAR",
                      "expression": "add(score, 1)",
                      "output_policy": "OUTPUT",
                      "order": 3,
                      "entity_scopes": ["ITEM"]
                    }
                  ]
                }
                """;
    }
}
