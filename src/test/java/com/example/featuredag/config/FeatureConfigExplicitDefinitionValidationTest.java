package com.example.featuredag.config;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.FeatureDefinition;

import org.junit.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

/** 覆盖显式 DAG 定义与历史宽松特征条目的校验边界。 */
public class FeatureConfigExplicitDefinitionValidationTest {

    @Test
    public void implicitLegacyEntriesAllowMissingKeyFields() {
        MappedFeatureSet mapped = map("""
                {
                  "feature_set_name": "explicit-definition-validation",
                  "version": "1",
                  "features": [
                    {
                      "catalog": "/unused/anonymous"
                    },
                    {
                      "name": "legacy_unused",
                      "raw_name": "",
                      "type": "",
                      "definition_type": " "
                    },
                    {
                      "name": "source",
                      "raw_name": "",
                      "type": "STRING",
                      "definition_type": "BASE",
                      "entity_scopes": ["USER"]
                    },
                    {
                      "name": "output",
                      "type": "STRING",
                      "definition_type": "DERIVED",
                      "expression": "source",
                      "output_policy": "OUTPUT",
                      "entity_scopes": ["USER"]
                    }
                  ]
                }
                """);

        assertEquals(3, mapped.definitions().size());
        FeatureDefinition legacy = definition(mapped, "legacy_unused");
        assertEquals(DataType.UNKNOWN, legacy.dataType());
        assertEquals("legacy_unused", legacy.sourceBinding());
        assertEquals("source", definition(mapped, "source").sourceBinding());
    }

    @Test
    public void explicitBaseStillRequiresType() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> map("""
                        {
                          "feature_set_name": "explicit-base-validation",
                          "version": "1",
                          "features": [
                            {
                              "name": "source",
                              "raw_name": "",
                              "definition_type": "BASE",
                              "entity_scopes": ["USER"]
                            },
                            {
                              "name": "output",
                              "type": "STRING",
                              "definition_type": "DERIVED",
                              "expression": "source",
                              "output_policy": "OUTPUT"
                            }
                          ]
                        }
                        """));

        assertEquals("type for feature source must not be blank", error.getMessage());
    }

    @Test
    public void explicitDerivedStillRequiresTypeAndExpression() {
        IllegalArgumentException missingType = assertThrows(
                IllegalArgumentException.class,
                () -> map(configWithDerived("""
                        {
                          "name": "output",
                          "definition_type": "DERIVED",
                          "expression": "source",
                          "output_policy": "OUTPUT"
                        }
                        """)));
        assertEquals("type for feature output must not be blank", missingType.getMessage());

        IllegalArgumentException missingExpression = assertThrows(
                IllegalArgumentException.class,
                () -> map(configWithDerived("""
                        {
                          "name": "output",
                          "type": "STRING",
                          "definition_type": "DERIVED",
                          "output_policy": "OUTPUT"
                        }
                        """)));
        assertEquals(
                "expression for DERIVED feature output must not be blank",
                missingExpression.getMessage());
    }

    @Test
    public void disabledEntriesAreFilteredBeforeDagFieldValidation() {
        MappedFeatureSet mapped = map("""
                {
                  "feature_set_name": "disabled-entry-filter",
                  "version": "1",
                  "features": [
                    {
                      "name": "disabled_invalid",
                      "to_use": false,
                      "definition_type": "DERIVED",
                      "type": "",
                      "expression": "",
                      "output_policy": "NOT_VALID",
                      "value_shap": "NOT_VALID",
                      "seq_max_length": 0,
                      "entity_scopes": [null]
                    },
                    {
                      "to_use": "",
                      "definition_type": "NOT_VALID"
                    },
                    {
                      "name": "source",
                      "type": "STRING",
                      "definition_type": "BASE",
                      "entity_scopes": ["USER"]
                    },
                    {
                      "name": "output",
                      "type": "STRING",
                      "definition_type": "DERIVED",
                      "expression": "source",
                      "output_policy": "OUTPUT",
                      "entity_scopes": ["USER"]
                    }
                  ]
                }
                """);

        assertEquals(2, mapped.definitions().size());
        assertFalse(mapped.definitions().stream()
                .anyMatch(definition -> definition.name().equals("disabled_invalid")));
    }

    @Test
    public void enabledDerivedStillRejectsDisabledDependency() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> map("""
                        {
                          "feature_set_name": "disabled-dependency",
                          "version": "1",
                          "features": [
                            {
                              "name": "disabled_source",
                              "to_use": false,
                              "definition_type": "BASE"
                            },
                            {
                              "name": "output",
                              "type": "STRING",
                              "definition_type": "DERIVED",
                              "expression": "disabled_source",
                              "output_policy": "OUTPUT"
                            }
                          ]
                        }
                        """));

        assertEquals(
                "Referenced feature is disabled: disabled_source (from output)",
                error.getMessage());
    }

    @Test
    public void requestedDisabledTargetKeepsDisabledDiagnostic() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> map("""
                        {
                          "feature_set_name": "disabled-target",
                          "version": "1",
                          "features": [
                            {
                              "name": "disabled_output",
                              "to_use": false,
                              "definition_type": "DERIVED"
                            },
                            {
                              "name": "source",
                              "type": "STRING",
                              "definition_type": "BASE",
                              "entity_scopes": ["USER"]
                            },
                            {
                              "name": "output",
                              "type": "STRING",
                              "definition_type": "DERIVED",
                              "expression": "source",
                              "output_policy": "OUTPUT",
                              "entity_scopes": ["USER"]
                            }
                          ]
                        }
                        """, Set.of("disabled_output")));

        assertEquals("Target feature is disabled: disabled_output", error.getMessage());
    }

    private static String configWithDerived(String derived) {
        return """
                {
                  "feature_set_name": "explicit-derived-validation",
                  "version": "1",
                  "features": [
                    {
                      "name": "source",
                      "raw_name": "source",
                      "type": "STRING",
                      "definition_type": "BASE",
                      "entity_scopes": ["USER"]
                    },
                    %s
                  ]
                }
                """.formatted(derived);
    }

    private static MappedFeatureSet map(String json) {
        return map(json, Set.of());
    }

    private static MappedFeatureSet map(String json, Set<String> targets) {
        return FeatureConfigMapper.map(
                FeatureConfigLoader.load(json), targets, Map.of());
    }

    private static FeatureDefinition definition(MappedFeatureSet mapped, String name) {
        return mapped.definitions().stream()
                .filter(definition -> definition.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
