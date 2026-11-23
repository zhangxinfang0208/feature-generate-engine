package com.example.featuredag.api;

import com.example.featuredag.config.FeatureOutputDescriptor;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 输出 Map 改为共享键布局后，对外仍是不可修改的 Map：与逐行 LinkedHashMap 的内容、顺序、
 * equals/hashCode/toString 和 JSON 序列化结果一致，所有修改方法无条件抛出。
 */
public class SharedKeyOutputMapTest {
    private static FeatureDagEngine engine(String config, ExecutionEnvironment environment) {
        return FeatureDagEngine.init(config, InitOptions.builder()
                .environment(environment).planId("shared-key-output-test").build());
    }

    @Test
    public void candidateMapsBehaveLikeLinkedHashMapsIncludingIterationOrder() {
        FeatureDagEngine engine = engine(OfflineBorrowedInputTest.CONFIG, ExecutionEnvironment.ONLINE);
        Map<String, List<?>> shared = OfflineBorrowedInputTest.row();
        List<Map<String, List<?>>> candidates = List.of(Map.of("t", List.of(1)), Map.of("t", List.of(2)));

        GenerateResult result = engine.generate(new OnlineGenerateRequest("single", shared, candidates));

        Map<String, List<?>> expectedShared = new LinkedHashMap<>();
        expectedShared.put("count", List.of(2));
        expectedShared.put("big", List.of(8L));
        assertEquals(expectedShared, result.featureValues());
        assertEquals(expectedShared.hashCode(), result.featureValues().hashCode());
        assertEquals(expectedShared.toString(), result.featureValues().toString());
        assertEquals(List.of("count", "big"), new ArrayList<>(result.featureValues().keySet()));

        Map<String, List<?>> expectedFirst = new LinkedHashMap<>();
        expectedFirst.put("picked", List.of(1, 1));
        Map<String, List<?>> actualFirst = result.candidateFeatureValues().get(0);
        assertEquals(expectedFirst, actualFirst);
        assertEquals(expectedFirst.hashCode(), actualFirst.hashCode());
        assertEquals(expectedFirst.toString(), actualFirst.toString());
        assertEquals(expectedFirst, new LinkedHashMap<>(actualFirst));
        assertEquals(expectedFirst, Map.copyOf(actualFirst));
        assertEquals(List.of(1, 1), actualFirst.get("picked"));
        assertEquals(List.of(1, 1), actualFirst.getOrDefault("picked", List.of(9)));
        assertNull(actualFirst.get("missing"));
        assertNull(actualFirst.get(42));
        assertNull(actualFirst.get(null));
        assertTrue(actualFirst.containsKey("picked"));
        assertFalse(actualFirst.containsKey("missing"));
        assertFalse(actualFirst.containsValue(null));
        assertEquals(1, actualFirst.size());
        assertFalse(actualFirst.isEmpty());
        assertEquals("picked", actualFirst.keySet().iterator().next());
        assertEquals(List.of(1, 1), actualFirst.values().iterator().next());
        assertEquals(actualFirst, actualFirst);
        assertNotEquals(actualFirst, result.candidateFeatureValues().get(1));
        assertEquals(expectedFirst, new LinkedHashMap<>(Map.of("picked", List.of(1, 1))));
    }

    @Test
    public void emptyCandidateMapsAndEmptyResultsStayUnmodifiableAndEmpty() {
        String noItemOutputs = """
                {"feature_set_name":"shared-only","version":"1","features":[
                  {"name":"x","definition_type":"BASE","type":"INT","value_shape":"SEQUENCE","entity_scopes":["USER"]},
                  {"name":"t","definition_type":"BASE","type":"INT","value_shape":"SCALAR","entity_scopes":["ITEM"]},
                  {"name":"count","definition_type":"DERIVED","type":"INT","value_shape":"SCALAR","expression":"count_distinct(x)","output_policy":"OUTPUT"}
                ]}
                """;
        FeatureDagEngine engine = engine(noItemOutputs, ExecutionEnvironment.ONLINE);
        GenerateResult twoCandidates = engine.generate(new OnlineGenerateRequest(
                "two", Map.of("x", List.of(1, 2)), List.of(Map.of("t", List.of(1)), Map.of("t", List.of(2)))));
        assertEquals(List.of(Map.of(), Map.of()), twoCandidates.candidateFeatureValues());
        assertTrue(twoCandidates.candidateFeatureValues().get(0).isEmpty());
        assertThrows(UnsupportedOperationException.class, twoCandidates.candidateFeatureValues()::clear);
        assertThrows(UnsupportedOperationException.class,
                () -> twoCandidates.candidateFeatureValues().get(0).put("picked", List.of(1)));

        GenerateResult zeroCandidates = engine.generate(new OnlineGenerateRequest(
                "zero", Map.of("x", List.of(1, 2)), List.of()));
        assertTrue(zeroCandidates.candidateFeatureValues().isEmpty());
        assertEquals(Map.of("count", List.of(2)), zeroCandidates.featureValues());
    }

    @Test
    public void everyMutationAttemptThrows() {
        FeatureDagEngine engine = engine(OfflineBorrowedInputTest.CONFIG, ExecutionEnvironment.ONLINE);
        GenerateResult result = engine.generate(new OnlineGenerateRequest(
                "single", OfflineBorrowedInputTest.row(), List.of(Map.of("t", List.of(1)))));
        for (Map<String, List<?>> map : List.of(result.featureValues(), result.candidateFeatureValues().get(0))) {
            assertThrows(UnsupportedOperationException.class, () -> map.put("picked", List.of(9)));
            assertThrows(UnsupportedOperationException.class, () -> map.putIfAbsent("picked", List.of(9)));
            assertThrows(UnsupportedOperationException.class, () -> map.putIfAbsent("missing", List.of(9)));
            assertThrows(UnsupportedOperationException.class, () -> map.remove("picked"));
            assertThrows(UnsupportedOperationException.class, () -> map.remove("picked", List.of(1, 1)));
            assertThrows(UnsupportedOperationException.class, () -> map.remove("missing", List.of(1, 1)));
            assertThrows(UnsupportedOperationException.class, map::clear);
            assertThrows(UnsupportedOperationException.class, () -> map.putAll(Map.of("picked", List.of(9))));
            assertThrows(UnsupportedOperationException.class, () -> map.replace("picked", List.of(9)));
            assertThrows(UnsupportedOperationException.class, () -> map.replace("picked", List.of(1, 1), List.of(9)));
            assertThrows(UnsupportedOperationException.class, () -> map.replaceAll((key, value) -> value));
            assertThrows(UnsupportedOperationException.class, () -> map.compute("picked", (key, value) -> value));
            assertThrows(UnsupportedOperationException.class, () -> map.computeIfAbsent("picked", key -> List.of(9)));
            assertThrows(UnsupportedOperationException.class, () -> map.computeIfPresent("picked", (key, value) -> value));
            assertThrows(UnsupportedOperationException.class, () -> map.merge("picked", List.of(9), (a, b) -> a));
            assertThrows(UnsupportedOperationException.class, () -> map.keySet().remove("picked"));
            assertThrows(UnsupportedOperationException.class, () -> map.values().clear());
            assertThrows(UnsupportedOperationException.class, () -> map.entrySet().clear());
            assertThrows(UnsupportedOperationException.class, () -> map.entrySet().removeIf(entry -> true));
            assertThrows(UnsupportedOperationException.class, () -> {
                java.util.Iterator<Map.Entry<String, List<?>>> iterator = map.entrySet().iterator();
                iterator.next();
                iterator.remove();
            });
            assertThrows(UnsupportedOperationException.class, () -> {
                java.util.Iterator<Map.Entry<String, List<?>>> iterator = map.entrySet().iterator();
                iterator.next().setValue(List.of(9));
            });
        }
        // 以上操作全部被拒绝后内容保持不变。
        assertEquals(List.of(1, 1), result.candidateFeatureValues().get(0).get("picked"));
        assertEquals(List.of(2), result.featureValues().get("count"));
    }

    @Test
    public void jacksonSerializesTheSameJsonAsLinkedHashMapRows() throws Exception {
        FeatureDagEngine engine = engine(OfflineBorrowedInputTest.CONFIG, ExecutionEnvironment.ONLINE);
        GenerateResult result = engine.generate(new OnlineGenerateRequest(
                "single", OfflineBorrowedInputTest.row(),
                List.of(Map.of("t", List.of(1)), Map.of("t", List.of(2)))));
        List<Map<String, List<?>>> expected = List.of(
                new LinkedHashMap<>(Map.of("picked", List.of(1, 1))),
                new LinkedHashMap<>(Map.of("picked", List.of(2))));
        ObjectMapper mapper = new ObjectMapper();

        assertEquals(mapper.writeValueAsString(expected),
                mapper.writeValueAsString(result.candidateFeatureValues()));
        assertEquals("{\"count\":[2],\"big\":[8]}", mapper.writeValueAsString(result.featureValues()));
        assertEquals(List.of(Map.of("count", List.of(2), "big", List.of(8L))), List.of(new LinkedHashMap<>(result.featureValues())));
    }

    @Test
    public void duplicateStoreNamesFollowLinkedHashMapPutSemantics() {
        List<FeatureOutputDescriptor> outputs = List.of(
                new FeatureOutputDescriptor("f1", "same", 0, 0),
                new FeatureOutputDescriptor("f2", "other", 1, 1),
                new FeatureOutputDescriptor("f3", "same", 2, 2));
        boolean[] candidateLevel = {true, true, true};
        FeatureOutputLayout layout = FeatureOutputLayout.forFamily(outputs, candidateLevel, true);
        FeatureOutputLayout.Rows rows = layout.newRows(2);
        rows.set(0, 0, List.of(1));
        rows.set(1, 0, List.of(2));
        rows.set(2, 0, List.of(3));
        rows.set(1, 1, List.of(7));
        rows.set(0, 1, List.of(5));

        // 第一行：后出现的 f3 覆盖 f1 的值；键顺序按首次出现排列。
        Map<String, List<?>> expected = new LinkedHashMap<>();
        expected.put("same", List.of(1));
        expected.put("other", List.of(2));
        expected.put("same", List.of(3));
        Map<String, List<?>> expectedSecond = new LinkedHashMap<>();
        expectedSecond.put("other", List.of(7));
        expectedSecond.put("same", List.of(5));

        Map<String, List<?>> first = rows.toMaps().get(0);
        Map<String, List<?>> second = rows.toMaps().get(1);
        assertEquals(expected, first);
        assertEquals(List.of("same", "other"), new ArrayList<>(first.keySet()));
        assertEquals(List.of(3), first.get("same"));
        assertEquals(List.of(2), first.get("other"));
        assertEquals(expectedSecond, second);
        // 同一族的所有行共用键布局，键顺序与输出声明顺序一致，与每行先写哪个输出无关。
        assertEquals(List.of("same", "other"), new ArrayList<>(second.keySet()));
        assertEquals(List.of(7), second.get("other"));
        assertThrows(UnsupportedOperationException.class, () -> first.put("other", List.of(9)));

        FeatureOutputLayout.Rows shared = FeatureOutputLayout.forFamily(outputs, candidateLevel, false).newRows(2);
        shared.set(0, 0, List.of(1));
        shared.setAll(2, List.of(3));
        assertEquals(List.of(Map.of(), Map.of()), shared.toMaps());
    }

    @Test
    public void layoutsAreSharedSafelyAcrossThreads() throws Exception {
        FeatureDagEngine engine = engine(OfflineBorrowedInputTest.CONFIG, ExecutionEnvironment.ONLINE);
        OnlineGenerateRequest request = new OnlineGenerateRequest(
                "shared", OfflineBorrowedInputTest.row(), List.of(Map.of("t", List.of(1))));
        GenerateResult expected = engine.generate(request);
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Callable<Void>> calls = new ArrayList<>();
            for (int index = 0; index < 64; index++) {
                calls.add(() -> {
                    GenerateResult result = engine.generate(request);
                    // 并发触发布局下标表的惰性构建。
                    assertEquals(List.of(1, 1), result.candidateFeatureValues().get(0).get("picked"));
                    assertEquals(expected.featureValues(), result.featureValues());
                    return null;
                });
            }
            for (var future : pool.invokeAll(calls)) future.get();
        }
    }
}
