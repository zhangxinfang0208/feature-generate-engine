package com.example.featuredag.api;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.FeatureDefinition;
import com.example.featuredag.definition.FeatureRole;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.runtime.ExecutionContext;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;

public class OnlineColumnarInputTest {
    private static final List<String> IDS = List.of("item_001", "item_002");
    private static final String CONFIG = """
            {"feature_set_name":"columnar-test","version":"1","features":[
              {"name":"history","definition_type":"BASE","type":"INT","value_shape":"SEQUENCE","entity_scopes":["USER"]},
              {"name":"price","definition_type":"BASE","type":"INT","entity_scopes":["ITEM"],"dft":7},
              {"name":"category","definition_type":"BASE","type":"STRING","entity_scopes":["ITEM"],"dft":"UNKNOWN"},
              {"name":"tags","definition_type":"BASE","type":"INT","value_shape":"SEQUENCE","entity_scopes":["ITEM"]},
              {"name":"total","definition_type":"DERIVED","type":"INT","expression":"add(price,1)"},
              {"name":"category_out","definition_type":"DERIVED","type":"STRING","expression":"lower(category)"},
              {"name":"picked","definition_type":"DERIVED","type":"INT","value_shape":"SEQUENCE","expression":"slice_by_indices(history,find_indices(history,price))"},
              {"name":"first","definition_type":"DERIVED","type":"INT","expression":"first_element(tags)","dft":-1},
              {"name":"tags_out","definition_type":"DERIVED","type":"INT","value_shape":"SEQUENCE","expression":"tags"},
              {"name":"history_out","definition_type":"DERIVED","type":"INT","value_shape":"SEQUENCE","expression":"history"}
            ]}
            """;

    private static FeatureDagEngine engine() {
        return FeatureDagEngine.init(CONFIG, InitOptions.builder()
                .environment(ExecutionEnvironment.ONLINE).planId("columnar-test").build());
    }

    private static Map<String, List<?>> shared() { return Map.of("history", List.of(1, 2, 1, 7)); }

    private static Map<String, List<?>> columns() {
        return Map.of("price", List.of(1, 2), "category", List.of("BOOK", "MUSIC"),
                "tags", List.of(List.of(10, 20), List.of()));
    }

    private static List<Map<String, List<?>>> rows() {
        return List.of(Map.of("price", List.of(1), "category", List.of("BOOK"), "tags", List.of(10, 20)),
                Map.of("price", List.of(2), "category", List.of("MUSIC"), "tags", List.of()));
    }

    @Test
    public void scalarSequenceSharedAndNativeBatchResultsMatchExistingEntry() {
        var engine = engine();
        GenerateResult expected = engine.generate(OnlineGenerateRequest.borrowed("rows", shared(), rows()));
        for (var request : List.of(new OnlineColumnarGenerateRequest("snapshot", shared(), IDS, columns()),
                OnlineColumnarGenerateRequest.borrowed("borrowed", shared(), IDS, columns()))) {
            GenerateResult actual = engine.generateColumnar(request);
            assertEquals(expected.featureValues(), actual.featureValues());
            assertEquals(expected.candidateFeatureValues(), actual.candidateFeatureValues());
            assertEquals(request.executionId(), actual.executionId());
            assertEquals(List.of(2L), actual.candidateFeatureValues().get(0).get("total"));
            assertEquals(List.of("music"), actual.candidateFeatureValues().get(1).get("category_out"));
            assertEquals(List.of(1, 1), actual.candidateFeatureValues().get(0).get("picked"));
            assertEquals(List.of(-1), actual.candidateFeatureValues().get(1).get("first"));
            assertEquals(List.of(), actual.candidateFeatureValues().get(1).get("tags_out"));
        }
    }

    @Test
    public void nullCellsMatchMissingRowKeysAndUseSourceDefaults() {
        var engine = engine();
        Map<String, List<?>> columns = new LinkedHashMap<>(columns());
        columns.put("price", Arrays.asList(null, 2));
        columns.put("category", Arrays.asList("BOOK", null));
        List<Map<String, List<?>>> rows = List.of(
                Map.of("category", List.of("BOOK"), "tags", List.of(10, 20)),
                Map.of("price", List.of(2), "tags", List.of()));
        var actual = engine.generateColumnar(OnlineColumnarGenerateRequest.borrowed("nulls", shared(), IDS, columns));
        var expected = engine.generate(new OnlineGenerateRequest("rows", shared(), rows));
        assertEquals(expected.candidateFeatureValues(), actual.candidateFeatureValues());
        assertEquals(List.of(8L), actual.candidateFeatureValues().get(0).get("total"));
        assertEquals(List.of("unknown"), actual.candidateFeatureValues().get(1).get("category_out"));
    }

    @Test
    public void absentColumnsStillPreserveCandidateCountAndDefaults() {
        var engine = FeatureDagEngine.init(CONFIG, InitOptions.builder()
                .environment(ExecutionEnvironment.ONLINE).targetFeatures(Set.of("total", "category_out")).build());
        var actual = engine.generateColumnar(new OnlineColumnarGenerateRequest("defaults", Map.of(), IDS, Map.of()));
        assertEquals(2, actual.candidateFeatureValues().size());
        for (var row : actual.candidateFeatureValues()) {
            assertEquals(List.of(8L), row.get("total"));
            assertEquals(List.of("unknown"), row.get("category_out"));
        }
    }

    @Test
    public void missingRequiredCellFailsWithCandidateIndexInsteadOfDerivedFallback() {
        Map<String, List<?>> columns = new LinkedHashMap<>(columns());
        columns.put("tags", Arrays.asList(List.of(10), null));
        FeatureGenerationException error = assertThrows(FeatureGenerationException.class,
                () -> engine().generateColumnar(OnlineColumnarGenerateRequest.borrowed("missing", shared(), IDS, columns)));
        assertTrue(error.getMessage(), error.getMessage().contains("tags"));
        assertTrue(error.getMessage(), error.getMessage().contains("candidate 1"));
    }

    @Test
    public void zeroCandidatesAndSharedOnlyPlansMatchExistingEntry() {
        var engine = engine();
        var actual = engine.generateColumnar(new OnlineColumnarGenerateRequest("empty", shared(), List.of(), Map.of()));
        var expected = engine.generate(new OnlineGenerateRequest("rows", shared(), List.of()));
        assertEquals(expected.featureValues(), actual.featureValues());
        assertTrue(actual.candidateFeatureValues().isEmpty());
        var sharedOnly = FeatureDagEngine.init(CONFIG, InitOptions.builder()
                .environment(ExecutionEnvironment.ONLINE).targetFeatures(Set.of("history_out")).build());
        var result = sharedOnly.generateColumnar(new OnlineColumnarGenerateRequest("shared", shared(), IDS, Map.of()));
        assertEquals(List.of(Map.of(), Map.of()), result.candidateFeatureValues());
        assertEquals(shared().get("history"), result.featureValues().get("history_out"));
    }

    @Test
    public void snapshotIsolatesNestedSequencesAndBorrowedOutputsAreDetached() {
        List<Integer> tags = new ArrayList<>(List.of(10, 20));
        List<List<Integer>> tagColumn = new ArrayList<>(List.of(tags, List.of()));
        Map<String, List<?>> columns = new LinkedHashMap<>(columns());
        columns.put("tags", tagColumn);
        List<String> ids = new ArrayList<>(IDS);
        var snapshot = new OnlineColumnarGenerateRequest("snapshot", shared(), ids, columns);
        var borrowed = OnlineColumnarGenerateRequest.borrowed("borrowed", shared(), ids, columns);
        assertSame(tagColumn, borrowed.candidateColumns().get("tags"));
        var engine = engine();
        var actual = engine.generateColumnar(borrowed);
        ids.clear(); tags.clear(); tagColumn.clear(); columns.clear();
        assertEquals(IDS, snapshot.candidateIds());
        assertEquals(IDS, borrowed.candidateIds());
        assertEquals(actual.candidateFeatureValues(), engine.generateColumnar(snapshot).candidateFeatureValues());
        assertEquals(List.of(10, 20), actual.candidateFeatureValues().get(0).get("tags_out"));
        assertThrows(UnsupportedOperationException.class, actual.candidateFeatureValues()::clear);
        assertThrows(UnsupportedOperationException.class, actual.candidateFeatureValues().get(0)::clear);
        assertThrows(UnsupportedOperationException.class, actual.candidateFeatureValues().get(0).get("tags_out")::clear);
        assertThrows(UnsupportedOperationException.class, snapshot.candidateColumns()::clear);
        assertThrows(UnsupportedOperationException.class, ((List<?>) snapshot.candidateColumns().get("tags").get(0))::clear);
    }

    @Test
    public void bigintScalarAndSequenceConversionMatchesRows() {
        var engine = FeatureDagEngine.init(CONFIG.replace("\"type\":\"INT\"", "\"type\":\"BIGINT\""),
                InitOptions.builder().environment(ExecutionEnvironment.ONLINE).build());
        Map<String, List<?>> columns = new LinkedHashMap<>(columns());
        columns.put("price", List.of(1, 2.0));
        columns.put("tags", List.of(List.of(10L, 20), List.of()));
        var actual = engine.generateColumnar(OnlineColumnarGenerateRequest.borrowed("bigint", shared(), IDS, columns));
        var expected = engine.generate(new OnlineGenerateRequest("rows", shared(), rows()));
        assertEquals(expected.candidateFeatureValues(), actual.candidateFeatureValues());
        assertEquals(List.of(10L, 20L), actual.candidateFeatureValues().get(0).get("tags_out"));
        columns.put("price", List.of(1L, 2.5));
        var error = assertThrows(FeatureGenerationException.class,
                () -> engine.generateColumnar(OnlineColumnarGenerateRequest.borrowed("bad", shared(), IDS, columns)));
        assertTrue(error.getMessage(), error.getMessage().contains("candidate 1"));
    }

    @Test
    public void eventSequencesPreserveNestedFieldsSnapshotAndOutputIsolation() {
        String config = """
                {"feature_set_name":"columnar-events","version":"1","features":[
                  {"name":"events","definition_type":"BASE","type":"EVENT_SEQUENCE","value_shape":"SEQUENCE","entity_scopes":["ITEM"]},
                  {"name":"out","definition_type":"DERIVED","type":"EVENT_SEQUENCE","value_shape":"SEQUENCE","expression":"events"}
                ]}
                """;
        var engine = FeatureDagEngine.init(config, InitOptions.builder().environment(ExecutionEnvironment.ONLINE).build());
        List<String> tags = new ArrayList<>(List.of("hot"));
        Map<String, Object> event = new LinkedHashMap<>(Map.of("tags", tags, "id", 1));
        Map<String, List<?>> columns = Map.of("events", List.of(List.of(event), List.of()));
        var snapshot = new OnlineColumnarGenerateRequest("events", Map.of(), IDS, columns);
        var actual = engine.generateColumnar(OnlineColumnarGenerateRequest.borrowed("borrowed", Map.of(), IDS, columns));
        var expected = engine.generate(new OnlineGenerateRequest("rows", Map.of(),
                List.of(Map.of("events", List.of(event)), Map.of("events", List.of()))));
        assertEquals(expected.candidateFeatureValues(), actual.candidateFeatureValues());
        tags.clear(); event.clear();
        assertEquals(expected.candidateFeatureValues(), engine.generateColumnar(snapshot).candidateFeatureValues());
        assertEquals(expected.candidateFeatureValues(), actual.candidateFeatureValues());
        var bad = OnlineColumnarGenerateRequest.borrowed("bad", Map.of(), IDS,
                Map.of("events", List.of(List.of("not an event"), List.of())));
        assertThrows(FeatureGenerationException.class, () -> engine.generateColumnar(bad));
    }

    @Test
    public void validatesColumnLengthsIdsAndSequenceCellShapes() {
        assertThrows(IllegalArgumentException.class,
                () -> new OnlineColumnarGenerateRequest("bad", shared(), IDS, Map.of("price", List.of(1))));
        assertThrows(IllegalArgumentException.class,
                () -> OnlineColumnarGenerateRequest.borrowed("bad", shared(), IDS, Map.of("price", List.of(1, 2, 3))));
        assertThrows(IllegalArgumentException.class,
                () -> new OnlineColumnarGenerateRequest(" ", Map.of(), IDS, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new OnlineColumnarGenerateRequest("bad", Map.of(), List.of(" "), Map.of()));
        Map<String, List<?>> nullColumn = new LinkedHashMap<>();
        nullColumn.put("price", null);
        assertThrows(NullPointerException.class,
                () -> OnlineColumnarGenerateRequest.borrowed("bad", Map.of(), IDS, nullColumn));
        Map<String, List<?>> columns = new LinkedHashMap<>(columns());
        columns.put("tags", List.of("10^20", "30"));
        var error = assertThrows(FeatureGenerationException.class,
                () -> engine().generateColumnar(OnlineColumnarGenerateRequest.borrowed("bad", shared(), IDS, columns)));
        assertTrue(error.getMessage(), error.getMessage().contains("candidate 0"));
    }

    @Test
    public void unusedColumnsAreNotDecodedAndLinkedColumnsKeepOrder() {
        Map<String, List<?>> columns = new LinkedHashMap<>(columns());
        columns.put("price", new LinkedList<>(List.of(1, 2)));
        columns.put("unused", List.of(new Object(), new Object()));
        var engine = engine();
        var expected = engine.generate(new OnlineGenerateRequest("rows", shared(), rows()));
        var actual = engine.generateColumnar(OnlineColumnarGenerateRequest.borrowed("linked", shared(), IDS, columns));
        assertEquals(expected.candidateFeatureValues(), actual.candidateFeatureValues());
    }

    @Test
    public void independentConcurrentRequestsKeepCandidateOrder() throws Exception {
        var engine = engine();
        var first = OnlineColumnarGenerateRequest.borrowed("a", shared(), IDS, columns());
        Map<String, List<?>> reversed = new LinkedHashMap<>();
        columns().forEach((name, values) -> {
            List<Object> copy = new ArrayList<>(values);
            Collections.reverse(copy);
            reversed.put(name, copy);
        });
        var second = OnlineColumnarGenerateRequest.borrowed("b", shared(), IDS.reversed(), reversed);
        var expectedFirst = engine.generateColumnar(first).candidateFeatureValues();
        var expectedSecond = new ArrayList<>(expectedFirst);
        Collections.reverse(expectedSecond);
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                boolean even = i % 2 == 0;
                tasks.add(() -> {
                    assertEquals(even ? expectedFirst : expectedSecond,
                            engine.generateColumnar(even ? first : second).candidateFeatureValues());
                    return null;
                });
            }
            for (var result : pool.invokeAll(tasks)) result.get();
        }
    }

    @Test
    public void offlineEngineRejectsTheNewOnlineEntry() {
        var engine = FeatureDagEngine.init(CONFIG, InitOptions.offline("offline"));
        assertThrows(FeatureGenerationException.class,
                () -> engine.generateColumnar(new OnlineColumnarGenerateRequest("bad", shared(), IDS, columns())));
    }

    @Test
    public void observationsReportCountsAndDecodeFailuresForNewEntry() {
        var observer = new com.example.featuredag.runtime.InMemoryRuntimeObserver(4);
        var engine = FeatureDagEngine.init(CONFIG, InitOptions.builder()
                .environment(ExecutionEnvironment.ONLINE).runtimeObserver(observer).build());
        engine.generateColumnar(OnlineColumnarGenerateRequest.borrowed("observed", shared(), IDS, columns()));
        assertEquals(2, observer.latest().candidateCount());
        assertEquals(1, observer.latest().groupCount());
        assertEquals(com.example.featuredag.runtime.ExecutionStatus.SUCCESS, observer.latest().status());
        Map<String, List<?>> columns = new LinkedHashMap<>(columns());
        columns.put("tags", List.of(1, 2));
        assertThrows(FeatureGenerationException.class,
                () -> engine.generateColumnar(OnlineColumnarGenerateRequest.borrowed("failed", shared(), IDS, columns)));
        assertEquals(com.example.featuredag.runtime.ExecutionPhase.DECODE, observer.latest().failurePhase());
        assertEquals(com.example.featuredag.runtime.ExecutionStatus.FAILED, observer.latest().status());
        assertEquals(2, observer.receivedCount());
    }

    @Test
    public void largeOnlinePlanMatchesIndependentOracleAndExistingEntry() throws Exception {
        var workload = new com.example.featuredag.performance.LargeOnlineWorkload(512, 300, 1);
        var engine = workload.engine(false);
        var rows = workload.request(0);
        Map<String, List<?>> columns = new LinkedHashMap<>();
        for (String name : engine.requiredCandidateInputNames()) {
            List<Object> values = new ArrayList<>();
            for (var row : rows.candidates()) values.add(row.get(name).get(0));
            columns.put(name, values);
        }
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < rows.candidates().size(); index++) ids.add("item_" + index);
        var result = engine.generateColumnar(OnlineColumnarGenerateRequest.borrowed(
                "large", rows.sharedValues(), ids, columns));
        assertEquals(engine.generate(rows).candidateFeatureValues(), result.candidateFeatureValues());
        for (int index = 0; index < ids.size(); index++) {
            assertEquals(workload.expected(0, index), result.candidateFeatureValues().get(index));
        }
    }

    @Test
    public void stableSequenceCellsAreBorrowedAndLegacyRowViewsRemainAvailable() {
        var feature = FeatureDefinition.builder().name("tags").role(FeatureRole.RAW)
                .dataType(DataType.INT).addEntityScope(EntityScope.ITEM).sourceBinding("tags")
                .declaredValueShape(ValueShape.SEQUENCE).build();
        var decoder = FeatureInputDecoder.from(new LogicalDagBuilder(new ExpressionParser(), OperatorRegistry.standard())
                .build(List.of(feature), Set.of("tags")));
        List<Integer> sequence = new ArrayList<>(List.of(1, 2));
        var decoded = decoder.decodeOnlineColumnsReadOnly(Map.of("tags", Arrays.asList(sequence, null)), 2);
        var context = ExecutionContext.onlineColumnarRequestFromOwnedDecodedValues("id", Map.of(), decoded, 2);
        assertEquals(2, context.candidateCount());
        assertEquals(List.of(1, 2), context.candidates().get(0).get("tags"));
        assertFalse(context.candidates().get(1).containsKey("tags"));
        assertEquals(Map.of(), context.candidates().get(1));
        assertEquals(2, context.candidateGroupEnd(0));
        assertEquals(0, context.candidateGroupIndex(1));
        assertThrows(UnsupportedOperationException.class, context.candidates().get(0)::clear);
        List<?> view = (List<?>) decoded.get("tags").get(0);
        assertThrows(UnsupportedOperationException.class, view::clear);
        // 仅在解码器单测中验证视图身份语义；真实执行期间禁止修改借用输入。
        sequence.set(0, 9);
        assertEquals(9, view.get(0));
    }
}
