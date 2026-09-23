package com.example.featuredag.api;

import com.example.featuredag.physical.ExecutionEnvironment;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;

public class OutputConstructionTest {
    private static final String CONFIG = """
            {"feature_set_name":"output-construction", "version":"1", "features":[
              {"name":"history","raw_name":"history","type":"INT","definition_type":"BASE",
               "value_shape":"SEQUENCE","entity_scopes":["USER"]},
              {"name":"events","raw_name":"events","type":"EVENT_SEQUENCE","definition_type":"BASE",
               "value_shape":"SEQUENCE","entity_scopes":["USER"]},
              {"name":"item","raw_name":"item","type":"INT","definition_type":"BASE",
               "value_shape":"SCALAR","entity_scopes":["ITEM"]},
              {"name":"history_out","expression":"history","type":"INT","definition_type":"DERIVED",
               "value_shape":"SEQUENCE","seq_max_length":3,"dft":0},
              {"name":"events_out","expression":"events","type":"EVENT_SEQUENCE","definition_type":"DERIVED",
               "value_shape":"SEQUENCE"},
              {"name":"item_out","expression":"item","type":"INT","definition_type":"DERIVED",
               "value_shape":"SCALAR"}
            ]}
            """;

    @Test
    public void publicConstructorKeepsDefensiveCopiesOrderAndNullElements() {
        List<Object> sharedList = new ArrayList<>(Arrays.asList(1, null));
        Map<String, List<?>> shared = new LinkedHashMap<>();
        shared.put("first", sharedList);
        shared.put("second", List.of(2));
        List<Object> itemList = new ArrayList<>(List.of(3));
        Map<String, List<?>> item = new LinkedHashMap<>();
        item.put("item", itemList);
        List<Map<String, List<?>>> items = new ArrayList<>(List.of(item));
        GenerateResult result = new GenerateResult("public", shared, items);
        sharedList.clear(); shared.clear(); itemList.clear(); item.clear(); items.clear();
        assertEquals(List.of("first", "second"), new ArrayList<>(result.featureValues().keySet()));
        assertEquals(Arrays.asList(1, null), result.featureValues().get("first"));
        assertEquals(List.of(3), result.candidateFeatureValues().getFirst().get("item"));
        assertFrozen(result);
        assertThrows(NullPointerException.class, () -> new GenerateResult(null, Map.of(), List.of()));
        Map<String, List<?>> invalid = new LinkedHashMap<>();
        invalid.put(null, List.of(1));
        assertThrows(NullPointerException.class, () -> new GenerateResult("bad", invalid, List.of()));
    }

    @Test
    public void singletonPreservesNullAndRejectsMutation() {
        List<?> value = FeatureValueCollections.singleton(null);
        assertEquals(Collections.singletonList(null), value);
        assertThrows(UnsupportedOperationException.class, value::clear);
        assertThrows(UnsupportedOperationException.class, () -> value.set(0, null));
    }

    @Test
    public void onlineAndGroupedOutputsRemainImmutableAndIsolated() {
        FeatureDagEngine engine = engine(ExecutionEnvironment.ONLINE);
        List<Object> tags = new ArrayList<>(List.of("hot"));
        Map<String, Object> nested = new LinkedHashMap<>(Map.of("score", 9));
        Map<String, Object> event = new LinkedHashMap<>(Map.of("tags", tags, "nested", nested));
        Map<String, List<?>> shared = shared(event);
        List<Map<String, List<?>>> candidates = List.of(item(7), item(8));
        GenerateResult first = engine.generate(new OnlineGenerateRequest("first", shared, candidates));
        var grouped = engine.generateBatch(new OnlineBatchGenerateRequest("batch", List.of(
                new OnlineRequestGroup("a", shared, candidates),
                new OnlineRequestGroup("b", shared, List.of(item(11))))));
        assertEquals(first.featureValues(), grouped.groupResults().get(0).featureValues());
        assertEquals(first.candidateFeatureValues(), grouped.groupResults().get(0).candidateFeatureValues());
        assertEquals(List.of(11), grouped.groupResults().get(1).candidateFeatureValues().getFirst().get("item_out"));
        assertEquals(List.of(1, 2, 0), first.featureValues().get("history_out"));
        assertEquals(List.of(7), first.candidateFeatureValues().getFirst().get("item_out"));
        assertFrozen(first);
        grouped.groupResults().forEach(OutputConstructionTest::assertFrozen);
        tags.add("changed"); nested.put("score", 10); event.clear();
        assertEvent(first.featureValues());
        assertEvent(grouped.groupResults().get(1).featureValues());
        engine.generate(new OnlineGenerateRequest("later", shared(Map.of("fresh", 1)), List.of(item(99))));
        assertEquals(List.of(7), first.candidateFeatureValues().getFirst().get("item_out"));
        assertEvent(first.featureValues());
        GenerateResult empty = engine.generate(new OnlineGenerateRequest("empty", shared(Map.of()), List.of()));
        assertTrue(empty.candidateFeatureValues().isEmpty());
    }

    @Test
    public void offlineSingleAndBatchKeepTheSameEncodedValues() {
        FeatureDagEngine engine = engine(ExecutionEnvironment.OFFLINE);
        Map<String, List<?>> row = new LinkedHashMap<>(shared(Map.of("tags", List.of("hot"), "nested", Map.of("score", 9))));
        row.putAll(item(4));
        GenerateResult single = engine.generate(new OfflineGenerateRequest("offline", row));
        var batch = engine.generateBatch(new OfflineBatchGenerateRequest("batch", List.of(row, row)));
        assertEquals(single.featureValues(), batch.rows().get(0));
        assertEquals(single.featureValues(), batch.rows().get(1));
        assertFrozen(single);
        assertEvent(single.featureValues());
    }

    @Test
    public void concurrentRequestsDoNotShareMutableResultContainers() throws Exception {
        FeatureDagEngine engine = engine(ExecutionEnvironment.ONLINE);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> engine.generate(new OnlineGenerateRequest(
                    "a", shared(Map.of("id", 1)), List.of(item(1)))));
            var b = executor.submit(() -> engine.generate(new OnlineGenerateRequest(
                    "b", shared(Map.of("id", 2)), List.of(item(2)))));
            GenerateResult first = a.get();
            GenerateResult second = b.get();
            assertEquals(List.of(1), first.candidateFeatureValues().getFirst().get("item_out"));
            assertEquals(List.of(2), second.candidateFeatureValues().getFirst().get("item_out"));
            assertEquals(List.of(Map.of("id", 1)), first.featureValues().get("events_out"));
            assertEquals(List.of(Map.of("id", 2)), second.featureValues().get("events_out"));
            assertFrozen(first); assertFrozen(second);
        } finally {
            executor.shutdownNow();
        }
    }

    private static FeatureDagEngine engine(ExecutionEnvironment environment) {
        return FeatureDagEngine.init(CONFIG, InitOptions.builder().environment(environment)
                .targetFeatures(Set.of("history_out", "events_out", "item_out")).build());
    }

    private static Map<String, List<?>> shared(Map<String, Object> event) {
        return Map.of("history", List.of(1, 2), "events", List.of(event));
    }

    private static Map<String, List<?>> item(int value) { return Map.of("item", List.of(value)); }

    private static void assertFrozen(GenerateResult result) {
        assertThrows(UnsupportedOperationException.class, () -> result.featureValues().put("extra", List.of()));
        assertThrows(UnsupportedOperationException.class, () -> result.candidateFeatureValues().add(Map.of()));
        for (List<?> values : result.featureValues().values()) {
            assertThrows(UnsupportedOperationException.class, values::clear);
        }
        for (Map<String, List<?>> row : result.candidateFeatureValues()) {
            assertThrows(UnsupportedOperationException.class, row::clear);
            row.values().forEach(values -> assertThrows(UnsupportedOperationException.class, values::clear));
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertEvent(Map<String, List<?>> values) {
        Map<String, Object> event = (Map<String, Object>) values.get("events_out").getFirst();
        assertEquals(List.of("hot"), event.get("tags"));
        assertEquals(Map.of("score", 9), event.get("nested"));
        assertThrows(UnsupportedOperationException.class, () -> event.put("extra", 1));
        assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) event.get("tags")).add("x"));
        assertThrows(UnsupportedOperationException.class, () -> ((Map<String, Object>) event.get("nested")).put("x", 1));
    }
}
