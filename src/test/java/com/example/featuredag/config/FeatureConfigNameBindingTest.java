package com.example.featuredag.config;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.FeatureGenerationException;
import com.example.featuredag.api.GenerateResult;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OfflineGenerateRequest;
import com.example.featuredag.definition.FeatureDefinition;
import com.example.featuredag.physical.ExecutionEnvironment;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 覆盖模型特征只按 name 绑定输入，raw_name 不再参与身份识别。 */
public final class FeatureConfigNameBindingTest {
    private static final String CONFIG = """
            {
              "feature_set_name": "name-binding-test",
              "version": "1",
              "features": [
                {
                  "name": "score",
                  "raw_name": "legacy_score",
                  "type": "INT",
                  "definition_type": "BASE",
                  "entity_scopes": ["USER"]
                },
                {
                  "name": "score_plus_one",
                  "type": "INT",
                  "definition_type": "DERIVED",
                  "expression": "add(score, 1)",
                  "output_policy": "OUTPUT",
                  "value_shape": "SCALAR",
                  "entity_scopes": ["USER"]
                }
              ]
            }
            """;

    @Test
    public void modelFeatureSourceBindingAlwaysUsesName() {
        MappedFeatureSet mapped = FeatureConfigMapper.map(
                FeatureConfigLoader.load(CONFIG), null, null);

        FeatureDefinition source = mapped.definitions().stream()
                .filter(definition -> definition.name().equals("score"))
                .findFirst()
                .orElseThrow();

        assertEquals("score", source.sourceBinding());
    }

    @Test
    public void publicApiReadsNameAndIgnoresRawName() {
        FeatureDagEngine engine = FeatureDagEngine.init(
                CONFIG,
                InitOptions.builder()
                        .environment(ExecutionEnvironment.OFFLINE)
                        .planId("name-binding-plan")
                        .build());

        GenerateResult result = engine.generate(new OfflineGenerateRequest(
                "name-input",
                Map.of("score", List.of(41))));
        assertEquals(List.of(42L), result.featureValues().get("score_plus_one"));

        FeatureGenerationException error = assertThrows(
                FeatureGenerationException.class,
                () -> engine.generate(new OfflineGenerateRequest(
                        "raw-name-input",
                        Map.of("legacy_score", List.of(41)))));
        assertTrue(error.getMessage().contains("Missing source feature: score"));
    }
}
