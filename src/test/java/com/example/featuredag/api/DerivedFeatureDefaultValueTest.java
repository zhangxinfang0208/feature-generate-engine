package com.example.featuredag.api;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

/** 覆盖模型配置中 DERIVED dft 对 null/空结果的统一单条与批量语义。 */
public class DerivedFeatureDefaultValueTest {

    @Test
    public void offlineDefaultFillsNullBlankAndEmptySequenceAndPropagatesDownstream() {
        FeatureDagEngine engine = FeatureDagEngine.init(
                defaultValueConfig(), InitOptions.offline("derived-default-offline"));

        GenerateResult nullAndEmpty = engine.generate(new OfflineGenerateRequest(
                "offline-null-empty", row(null, List.of())));
        assertFallbackValues(nullAndEmpty.featureValues());
        assertNull(nullAndEmpty.featureValues().get("without_default").get(0));

        GenerateResult blank = engine.generate(new OfflineGenerateRequest(
                "offline-blank", row("", List.of())));
        assertFallbackValues(blank.featureValues());
        assertEquals(List.of(""), blank.featureValues().get("without_default"));

        GenerateResult present = engine.generate(new OfflineGenerateRequest(
                "offline-present", row("actual", List.of("actual-item"))));
        assertEquals(List.of("actual"), present.featureValues().get("scalar_with_default"));
        assertEquals(
                List.of("actual-item", "fallback-item", "fallback-item", "fallback-item"),
                present.featureValues().get("sequence_with_default"));
        assertEquals(
                Arrays.asList("actual-item", null, null, null),
                present.featureValues().get("sequence_without_default"));
        assertEquals(List.of(1), present.featureValues().get("fallback_sequence_length"));

        GenerateResult truncated = engine.generate(new OfflineGenerateRequest(
                "offline-truncated",
                row("actual", List.of("one", "two", "three", "four", "five"))));
        assertEquals(
                List.of("one", "two", "three", "four"),
                truncated.featureValues().get("sequence_with_default"));
    }

    @Test
    public void offlineAndOnlineBatchApplyDefaultPerRowAndCandidate() {
        List<Map<String, List<?>>> rows = List.of(
                row(null, List.of()),
                row("", List.of()),
                row("actual", List.of("actual-item")));

        FeatureDagEngine offline = FeatureDagEngine.init(
                defaultValueConfig(), InitOptions.offline("derived-default-offline-batch"));
        OfflineBatchGenerateResult offlineResult = offline.generateBatch(
                new OfflineBatchGenerateRequest("offline-batch", rows));
        assertFallbackValues(offlineResult.rows().get(0));
        assertFallbackValues(offlineResult.rows().get(1));
        assertEquals(
                List.of("actual"),
                offlineResult.rows().get(2).get("scalar_with_default"));
        assertEquals(
                List.of("actual-item", "fallback-item", "fallback-item", "fallback-item"),
                offlineResult.rows().get(2).get("sequence_with_default"));

        FeatureDagEngine online = FeatureDagEngine.init(
                defaultValueConfig(), InitOptions.online("derived-default-online"));
        GenerateResult onlineResult = online.generate(new OnlineGenerateRequest(
                "online-candidates", Map.of(), rows));
        assertFallbackValues(onlineResult.candidateFeatureValues().get(0));
        assertFallbackValues(onlineResult.candidateFeatureValues().get(1));
        assertEquals(
                List.of("actual"),
                onlineResult.candidateFeatureValues().get(2).get("scalar_with_default"));

        OnlineBatchGenerateResult groupedResult = online.generateBatch(
                new OnlineBatchGenerateRequest(
                        "online-groups",
                        List.of(
                                new OnlineRequestGroup(
                                        "group-0", Map.of(), rows.subList(0, 2)),
                                new OnlineRequestGroup(
                                        "group-1", Map.of(), rows.subList(2, 3)))));
        assertFallbackValues(
                groupedResult.groupResults().get(0).candidateFeatureValues().get(0));
        assertFallbackValues(
                groupedResult.groupResults().get(0).candidateFeatureValues().get(1));
        assertEquals(
                List.of("actual"),
                groupedResult.groupResults().get(1).candidateFeatureValues().get(0)
                        .get("scalar_with_default"));
    }

    @Test
    public void calculationExceptionIsNotMaskedByDerivedDefault() {
        String config = """
                {
                  "feature_set_name": "derived-default-error",
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
                      "type": "DOUBLE",
                      "definition_type": "DERIVED",
                      "value_shape": "SCALAR",
                      "expression": "log_base(score, 2, 16)",
                      "dft": 99.0,
                      "output_policy": "OUTPUT"
                    }
                  ]
                }
                """;
        FeatureDagEngine engine = FeatureDagEngine.init(
                config, InitOptions.offline("derived-default-error"));

        assertThrows(
                FeatureGenerationException.class,
                () -> engine.generate(new OfflineGenerateRequest(
                        "error-case", Map.of("score", List.of(0.0)))));
    }

    private static void assertFallbackValues(Map<String, List<?>> values) {
        assertEquals(List.of("fallback"), values.get("scalar_with_default"));
        assertEquals(
                List.of("fallback-item", "fallback-item", "fallback-item", "fallback-item"),
                values.get("sequence_with_default"));
        assertEquals(
                Collections.nCopies(4, null),
                values.get("sequence_without_default"));
        // sequence_with_default 是下游输入，证明默认值在特征边界生效而非仅改写最终编码。
        assertEquals(List.of(1), values.get("fallback_sequence_length"));
    }

    private static Map<String, List<?>> row(Object scalar, List<?> sequence) {
        Map<String, List<?>> values = new LinkedHashMap<>();
        List<Object> scalarValues = new ArrayList<>(1);
        scalarValues.add(scalar);
        values.put("scalar_source", scalarValues);
        values.put("sequence_source", sequence);
        return values;
    }

    private static String defaultValueConfig() {
        return """
                {
                  "feature_set_name": "derived-default-values",
                  "version": "1",
                  "features": [
                    {
                      "name": "scalar_source",
                      "raw_name": "scalar_source",
                      "type": "STRING",
                      "definition_type": "BASE",
                      "value_shape": "SCALAR",
                      "entity_scopes": ["ITEM"]
                    },
                    {
                      "name": "sequence_source",
                      "raw_name": "sequence_source",
                      "type": "STRING",
                      "definition_type": "BASE",
                      "value_shape": "SEQUENCE",
                      "entity_scopes": ["ITEM"]
                    },
                    {
                      "name": "scalar_with_default",
                      "store_name": "scalar_with_default",
                      "type": "STRING",
                      "definition_type": "DERIVED",
                      "value_shape": "SCALAR",
                      "expression": "scalar_source",
                      "dft": "fallback",
                      "output_policy": "OUTPUT",
                      "order": 1
                    },
                    {
                      "name": "without_default",
                      "store_name": "without_default",
                      "type": "STRING",
                      "definition_type": "DERIVED",
                      "value_shape": "SCALAR",
                      "expression": "scalar_source",
                      "dft": null,
                      "output_policy": "OUTPUT",
                      "order": 2
                    },
                    {
                      "name": "sequence_with_default",
                      "store_name": "sequence_with_default",
                      "type": "STRING",
                      "definition_type": "DERIVED",
                      "value_shape": "SEQUENCE",
                      "seq_max_length": 4,
                      "expression": "sequence_source",
                      "dft": "fallback-item",
                      "output_policy": "OUTPUT",
                      "order": 3
                    },
                    {
                      "name": "sequence_without_default",
                      "store_name": "sequence_without_default",
                      "type": "STRING",
                      "definition_type": "DERIVED",
                      "value_shape": "SEQUENCE",
                      "seq_max_length": 4,
                      "expression": "sequence_source",
                      "dft": null,
                      "output_policy": "OUTPUT",
                      "order": 4
                    },
                    {
                      "name": "fallback_sequence_length",
                      "store_name": "fallback_sequence_length",
                      "type": "INT",
                      "definition_type": "DERIVED",
                      "value_shape": "SCALAR",
                      "expression": "get_seq_length(sequence_with_default)",
                      "dft": -1,
                      "output_policy": "OUTPUT",
                      "order": 5
                    }
                  ]
                }
                """;
    }
}
