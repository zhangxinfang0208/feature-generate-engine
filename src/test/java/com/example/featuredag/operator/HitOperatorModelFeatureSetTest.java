package com.example.featuredag.operator;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.GenerateResult;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OfflineGenerateRequest;
import com.example.featuredag.physical.ExecutionEnvironment;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class HitOperatorModelFeatureSetTest {
    @Test
    public void consumesAnonymousNestedIntermediateOperatorResult() {
        FeatureDagEngine engine = FeatureDagEngine.init(
                modelJson("hit(slice_by_indices(seq_kv, selected_indices), seq_key)", false),
                offlineOptions("hit-inline-intermediate"));
        Map<String, Object> first = event("a", 1);
        Map<String, Object> second = event("b", 2);
        Map<String, Object> third = event("a", 3);
        Map<String, Object> fourth = event("c", 4);

        GenerateResult result = engine.generate(new OfflineGenerateRequest(
                "inline-case",
                inputs(
                        Arrays.asList(first, second, third, fourth),
                        Arrays.asList(0, 2, 3),
                        Arrays.asList("a", "c"))));

        assertEquals(
                Arrays.asList(first, third, fourth),
                result.featureValues().get("hit_output"));
    }

    @Test
    public void executesNamedInternalIntermediateWithoutPublishingIt() {
        FeatureDagEngine engine = FeatureDagEngine.init(
                modelJson("hit(sliced_seq_kv, seq_key)", true),
                offlineOptions("hit-named-intermediate"));
        Map<String, Object> first = event("a", 1);
        Map<String, Object> second = event("b", 2);
        Map<String, Object> third = event("a", 3);

        GenerateResult result = engine.generate(new OfflineGenerateRequest(
                "internal-case",
                inputs(
                        Arrays.asList(first, second, third),
                        Arrays.asList(0, 1, 2),
                        Arrays.asList("a"))));

        assertEquals(
                Arrays.asList(first, third),
                result.featureValues().get("hit_output"));
        assertFalse(result.featureValues().containsKey("sliced_seq_kv"));
    }

    @Test
    public void supportsNamedArgumentsInModelExpression() {
        assertEquals(
                Arrays.asList("seq_kv", "seq_key"),
                OperatorRegistry.standard().require("hit").parameterNames());
        FeatureDagEngine engine = FeatureDagEngine.init(
                modelJson(
                        "hit(seq_kv=slice_by_indices(seq_kv, selected_indices), seq_key=seq_key)",
                        false),
                offlineOptions("hit-named-arguments"));
        Map<String, Object> first = event("a", 1);
        Map<String, Object> second = event("b", 2);

        GenerateResult result = engine.generate(new OfflineGenerateRequest(
                "named-argument-case",
                inputs(
                        Arrays.asList(first, second),
                        Arrays.asList(0, 1),
                        Arrays.asList("b"))));

        assertEquals(
                Arrays.asList(second),
                result.featureValues().get("hit_output"));
    }

    private static String modelJson(String outputExpression, boolean namedIntermediate) {
        String intermediate = namedIntermediate
                ? """
                    ,{
                      "name": "sliced_seq_kv",
                      "type": "EVENT_SEQUENCE",
                      "definition_type": "DERIVED",
                      "expression": "slice_by_indices(seq_kv, selected_indices)",
                      "value_shape": "SEQUENCE",
                      "output_policy": "INTERNAL_ONLY"
                    }
                    """
                : "";
        return """
                {
                  "feature_set_name": "hit-model-test",
                  "version": "1.0",
                  "features": [
                    {
                      "name": "seq_kv",
                      "raw_name": "seq_kv",
                      "type": "EVENT_SEQUENCE",
                      "definition_type": "BASE",
                      "value_shape": "SEQUENCE",
                      "entity_scopes": ["USER"]
                    },
                    {
                      "name": "selected_indices",
                      "raw_name": "selected_indices",
                      "type": "INT",
                      "definition_type": "BASE",
                      "value_shape": "SEQUENCE",
                      "entity_scopes": ["USER"]
                    },
                    {
                      "name": "seq_key",
                      "raw_name": "seq_key",
                      "type": "STRING",
                      "definition_type": "BASE",
                      "value_shape": "SEQUENCE",
                      "entity_scopes": ["USER"]
                    }
                    %s,
                    {
                      "name": "hit_output",
                      "type": "EVENT_SEQUENCE",
                      "definition_type": "DERIVED",
                      "expression": "%s",
                      "value_shape": "SEQUENCE",
                      "output_policy": "OUTPUT",
                      "order": 1
                    }
                  ]
                }
                """.formatted(intermediate, outputExpression);
    }

    private static InitOptions offlineOptions(String planId) {
        return InitOptions.builder()
                .environment(ExecutionEnvironment.OFFLINE)
                .planId(planId)
                .build();
    }

    private static Map<String, List<?>> inputs(
            List<Map<String, Object>> events,
            List<Integer> indices,
            List<String> keys) {
        Map<String, List<?>> values = new LinkedHashMap<String, List<?>>();
        values.put("seq_kv", events);
        values.put("selected_indices", indices);
        values.put("seq_key", keys);
        return values;
    }

    private static Map<String, Object> event(String key, int value) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("key", key);
        event.put("value", Integer.valueOf(value));
        return event;
    }
}
