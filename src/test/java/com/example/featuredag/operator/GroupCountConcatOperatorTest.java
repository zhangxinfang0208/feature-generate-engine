package com.example.featuredag.operator;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.GenerateResult;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OfflineGenerateRequest;
import com.example.featuredag.operator.builtin.GroupCountConcatOperator;
import com.example.featuredag.physical.ExecutionEnvironment;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class GroupCountConcatOperatorTest {
    @Test
    public void groupsByValueAndPreservesFirstOccurrenceOrder() {
        GroupCountConcatOperator operator = new GroupCountConcatOperator();

        Object result = operator.evaluate(Collections.<Object>singletonList(
                Arrays.asList("creative-a", "creative-b", "creative-a", "creative-c",
                        "creative-b")));

        assertEquals(
                Arrays.asList("creative-a#2", "creative-b#2", "creative-c#1"),
                result);
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) result).clear());
    }

    @Test
    public void supportsEmptySequenceAndCustomDelimiter() {
        GroupCountConcatOperator operator = new GroupCountConcatOperator();
        Map<String, Object> config = Collections.<String, Object>singletonMap("delimiter", "|");

        assertEquals(
                Collections.emptyList(),
                operator.evaluate(Arrays.<Object>asList(Collections.emptyList(), config)));
        assertEquals(
                Arrays.asList("10|2", "20|1"),
                operator.evaluate(Arrays.<Object>asList(Arrays.asList(10, 20, 10), config)));
    }

    @Test
    public void rejectsNonObjectConfigAndStructuredEventElements() {
        GroupCountConcatOperator operator = new GroupCountConcatOperator();

        IllegalArgumentException configFailure = assertThrows(
                IllegalArgumentException.class,
                () -> operator.evaluate(Arrays.<Object>asList(
                        Collections.singletonList("a"), "#")));
        assertTrue(configFailure.getMessage().contains("object config"));

        IllegalArgumentException eventFailure = assertThrows(
                IllegalArgumentException.class,
                () -> operator.evaluate(Collections.<Object>singletonList(
                        Collections.singletonList(
                                Collections.<String, Object>singletonMap("id", "a")))));
        assertTrue(eventFailure.getMessage().contains("event sequence"));
    }

    @Test
    public void usesScalarBatchAdapterWithRowEquivalentResults() {
        OperatorRegistry registry = new OperatorRegistry().register(
                new GroupCountConcatOperator());
        Map<String, Object> config = Collections.<String, Object>singletonMap("delimiter", "#");
        BatchOperatorCall call = new BatchOperatorCall(
                new OfflineLayout(2),
                Arrays.<BatchColumn>asList(
                        new ListBatchColumn(Arrays.<Object>asList(
                                Arrays.asList("a", "a", "b"),
                                Arrays.asList("c", "d", "c"))),
                        new ListBatchColumn(Arrays.<Object>asList(config, config))));

        assertEquals(BatchKernelKind.SCALAR_ADAPTER,
                registry.batchKernelKind("group_count_concat"));
        assertEquals(
                Arrays.asList(
                        Arrays.asList("a#2", "b#1"),
                        Arrays.asList("c#2", "d#1")),
                ((ListBatchColumn) registry.evaluateBatch(
                        "group_count_concat", call).values()).values());
    }

    @Test
    public void executesCategoryFilterAndGroupingThroughStandardRegistry() {
        String configJson = """
                {
                  "feature_set_name": "category_behavior_strength",
                  "version": "1.0",
                  "features": [
                    {
                      "name": "click_app_categories",
                      "raw_name": "click_app_categories",
                      "type": "STRING",
                      "definition_type": "BASE",
                      "to_use": true,
                      "entity_scopes": ["USER"],
                      "value_shape": "SEQUENCE",
                      "seq_max_length": 1024
                    },
                    {
                      "name": "click_ids",
                      "raw_name": "click_ids",
                      "type": "STRING",
                      "definition_type": "BASE",
                      "to_use": true,
                      "entity_scopes": ["USER"],
                      "value_shape": "SEQUENCE",
                      "seq_max_length": 1024
                    },
                    {
                      "name": "ecommerce_click_strength",
                      "store_name": "ecommerce_click_strength",
                      "type": "STRING",
                      "definition_type": "DERIVED",
                      "expression": "group_count_concat(slice_by_indices(click_ids, find_indices(click_app_categories, '电商')), {\\\"delimiter\\\":\\\"#\\\"})",
                      "output_policy": "OUTPUT",
                      "to_use": true,
                      "entity_scopes": ["USER"],
                      "value_shape": "SEQUENCE",
                      "seq_max_length": 2
                    }
                  ]
                }
                """;
        InitOptions options = InitOptions.builder()
                .environment(ExecutionEnvironment.OFFLINE)
                .build();
        FeatureDagEngine engine = FeatureDagEngine.init(configJson, options);

        Map<String, List<?>> inputs = new LinkedHashMap<String, List<?>>();
        inputs.put("click_app_categories",
                Arrays.asList("电商", "游戏", "电商", "电商", "工具"));
        inputs.put("click_ids",
                Arrays.asList("creative-a", "creative-x", "creative-a", "creative-b",
                        "creative-y"));
        GenerateResult result = engine.generate(new OfflineGenerateRequest("case-1", inputs));

        assertEquals(
                Arrays.asList("creative-a#2", "creative-b#1"),
                result.featureValues().get("ecommerce_click_strength"));
        assertTrue(OperatorRegistry.standard().find("group_count_concat").isPresent());
    }

    private static final class OfflineLayout implements BatchLayout {
        private final int rowCount;

        private OfflineLayout(int rowCount) {
            this.rowCount = rowCount;
        }

        @Override
        public BatchDomain domain() {
            return BatchDomain.OFFLINE_ROW;
        }

        @Override
        public int rowCount() {
            return rowCount;
        }

        @Override
        public int groupIndexAt(int rowIndex) {
            return -1;
        }

        @Override
        public int indexInGroupAt(int rowIndex) {
            return rowIndex;
        }
    }
}
