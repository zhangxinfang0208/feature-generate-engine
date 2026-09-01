package com.example.featuredag.api;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** 覆盖算子异常在公共 API 边界的默认值恢复与无默认值异常定位。 */
public final class DerivedFeatureOperatorFallbackTest {

    @Test
    public void publicOfflineBatchUsesDefaultForOnlyInvalidLogRow() {
        FeatureDagEngine engine = FeatureDagEngine.init(
                logConfig("\"dft\": 99.0,"),
                InitOptions.offline("operator-fallback-public"));

        OfflineBatchGenerateResult result = engine.generateBatch(
                new OfflineBatchGenerateRequest(
                        "public-batch",
                        List.of(
                                Map.of("score", List.of(4.0)),
                                Map.of("score", List.of(0.0)),
                                Map.of("score", List.of(8.0)))));

        assertEquals(List.of(2.0), result.rows().get(0).get("score_log"));
        assertEquals(List.of(99.0), result.rows().get(1).get("score_log"));
        assertEquals(List.of(3.0), result.rows().get(2).get("score_log"));
    }

    @Test
    public void noDefaultPreservesFeatureNameAndOriginalCause() {
        FeatureDagEngine engine = FeatureDagEngine.init(
                logConfig(""),
                InitOptions.offline("operator-no-default"));

        try {
            engine.generate(new OfflineGenerateRequest(
                    "invalid-log", Map.of("score", List.of(0.0))));
            fail("Expected FeatureGenerationException");
        } catch (FeatureGenerationException failure) {
            assertEquals("score_log", failure.featureName());
            assertTrue(rootCause(failure) instanceof IllegalArgumentException);
            assertEquals(
                    "log_base value must be greater than zero",
                    rootCause(failure).getMessage());
        }
    }

    static String logConfig(String defaultProperty) {
        return """
                {
                  "feature_set_name": "operator-fallback-public",
                  "version": "1",
                  "features": [
                    {
                      "name": "score",
                      "raw_name": "score",
                      "type": "DOUBLE",
                      "definition_type": "BASE",
                      "value_shape": "SCALAR",
                      "entity_scopes": ["USER"]
                    },
                    {
                      "name": "score_log",
                      "store_name": "score_log",
                      "type": "DOUBLE",
                      "definition_type": "DERIVED",
                      "value_shape": "SCALAR",
                      "expression": "log_base(score, 2, 16)",
                      %s
                      "output_policy": "OUTPUT"
                    }
                  ]
                }
                """.formatted(defaultProperty);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable result = error;
        while (result.getCause() != null) result = result.getCause();
        return result;
    }
}
