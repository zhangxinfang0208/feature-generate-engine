package com.example.featuredag.config;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 覆盖公共配置中布尔值的宽松输入契约。 */
public class FlexibleBooleanDeserializerTest {

    @Test
    public void blankStringsDefaultToFalse() {
        FeatureConfig feature = loadFeature("""
                {
                  "name": "legacy",
                  "to_use": "",
                  "is_feedback": "   "
                }
                """);

        assertFalse(feature.toUse());
        assertFalse(feature.isFeedback());
    }

    @Test
    public void booleansAndBooleanStringsRetainTheirValues() {
        FeatureConfig trueFeature = loadFeature("""
                {
                  "name": "enabled",
                  "to_use": true,
                  "is_feedback": "true"
                }
                """);
        FeatureConfig falseFeature = loadFeature("""
                {
                  "name": "disabled",
                  "to_use": "false",
                  "is_feedback": false
                }
                """);

        assertTrue(trueFeature.toUse());
        assertTrue(trueFeature.isFeedback());
        assertFalse(falseFeature.toUse());
        assertFalse(falseFeature.isFeedback());
    }

    @Test
    public void missingAndNullValuesRemainUnset() {
        FeatureConfig missing = loadFeature("""
                {
                  "name": "missing"
                }
                """);
        FeatureConfig explicitNull = loadFeature("""
                {
                  "name": "nulls",
                  "to_use": null,
                  "is_feedback": null
                }
                """);

        assertNull(missing.toUse());
        assertNull(missing.isFeedback());
        assertNull(explicitNull.toUse());
        assertNull(explicitNull.isFeedback());
    }

    @Test
    public void nonBooleanTextStillFails() {
        assertThrows(
                IllegalArgumentException.class,
                () -> loadFeature("""
                        {
                          "name": "invalid",
                          "to_use": "yes"
                        }
                        """));
    }

    private static FeatureConfig loadFeature(String featureJson) {
        String json = """
                {
                  "feature_set_name": "boolean-config",
                  "version": "1",
                  "features": [%s]
                }
                """.formatted(featureJson);
        return FeatureConfigLoader.load(json).features().get(0);
    }
}
