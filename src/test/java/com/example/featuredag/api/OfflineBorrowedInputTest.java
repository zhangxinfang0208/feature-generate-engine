package com.example.featuredag.api;

import com.example.featuredag.physical.PhysicalPlan;
import com.example.featuredag.runtime.ExecutionResult;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class OfflineBorrowedInputTest {
    public static final String CONFIG = """
            {"feature_set_name":"offline-borrowed-test","version":"1","features":[
              {"name":"x","definition_type":"BASE","type":"INT","value_shape":"SEQUENCE","entity_scopes":["USER"]},
              {"name":"t","definition_type":"BASE","type":"INT","value_shape":"SCALAR","entity_scopes":["ITEM"]},
              {"name":"b","definition_type":"BASE","type":"BIGINT","value_shape":"SCALAR","entity_scopes":["USER"]},
              {"name":"picked","definition_type":"DERIVED","type":"INT","value_shape":"SEQUENCE","expression":"slice_by_indices(x, find_indices(x, t))","output_policy":"OUTPUT"},
              {"name":"count","definition_type":"DERIVED","type":"INT","value_shape":"SCALAR","expression":"count_distinct(x)","output_policy":"OUTPUT"},
              {"name":"big","definition_type":"DERIVED","type":"BIGINT","value_shape":"SCALAR","expression":"add(b, 1)","output_policy":"OUTPUT"}
            ]}
            """;

    public static Map<String, List<?>> row() {
        Map<String, List<?>> row = new LinkedHashMap<>();
        row.put("x", new ArrayList<>(List.of(1, 2, 1)));
        row.put("t", List.of(1));
        row.put("b", List.of(7));
        return row;
    }

    @Test
    public void snapshotsStayStableAndBorrowedOutputsAreDetached() {
        Map<String, List<?>> input = row();
        var snapshot = new OfflineBatchGenerateRequest("snapshot", List.of(input));
        var borrowed = OfflineBatchGenerateRequest.borrowed("borrowed", List.of(input));
        assertSame(input.get("x"), borrowed.rows().get(0).get("x"));
        FeatureDagEngine engine = FeatureDagEngine.init(CONFIG, InitOptions.offline("test"));
        var first = engine.generateBatch(borrowed).rows();
        assertEquals(List.of(1, 1), first.get(0).get("picked"));
        assertEquals(List.of(2), first.get(0).get("count"));
        assertEquals(List.of(8L), first.get(0).get("big"));
        assertEquals(List.of(1, 2, 1), input.get("x"));
        input.get("x").clear();
        assertEquals(first, engine.generateBatch(snapshot).rows());
        assertEquals(List.of(1, 1), first.get(0).get("picked"));
        assertThrows(UnsupportedOperationException.class, first::clear);
        assertThrows(UnsupportedOperationException.class, first.get(0)::clear);
        assertThrows(UnsupportedOperationException.class, first.get(0).get("picked")::clear);
    }

    @Test
    public void borrowedSingleAndBatchKeepRowsAndConcurrentRequestIsolation() throws Exception {
        var engine = FeatureDagEngine.init(CONFIG, InitOptions.offline("concurrent"));
        Map<String, List<?>> row1 = row(), row2 = row();
        row2.put("t", List.of(2));
        var request = OfflineBatchGenerateRequest.borrowed("batch", List.of(row1, row2));
        var expected = engine.generateBatch(request).rows();
        assertEquals(expected.get(0), engine.generate(OfflineGenerateRequest.borrowed("single", row1)).featureValues());
        assertEquals(List.of(2), expected.get(1).get("picked"));
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Callable<List<Map<String, List<?>>>>> calls = new ArrayList<>();
            for (int i = 0; i < 16; i++) calls.add(() -> engine.generateBatch(request).rows());
            for (var future : executor.invokeAll(calls)) assertEquals(expected, future.get());
        }
    }

    @Test
    public void traceRetainsEveryIntermediateResult() {
        AtomicReference<ExecutionResult> trace = new AtomicReference<>();
        AtomicReference<PhysicalPlan> plan = new AtomicReference<>();
        var engine = FeatureDagEngine.init(CONFIG, InitOptions.builder()
                .environment(com.example.featuredag.physical.ExecutionEnvironment.OFFLINE)
                .runtimeTraceObserver((id, physical, result) -> { plan.set(physical); trace.set(result); }).build());
        engine.generateBatch(OfflineBatchGenerateRequest.borrowed("trace", List.of(row())));
        assertEquals(plan.get().nodes().size(), trace.get().nodeStates().size());
        trace.get().nodeStates().values().forEach(state -> assertNotNull(state.resultHandle()));
    }

    @Test
    public void publicResultConstructorStillSnapshotsExternalContainers() {
        Map<String, List<?>> input = row();
        var result = new OfflineBatchGenerateResult("external", List.of(input));
        input.get("x").clear();
        input.clear();
        assertEquals(List.of(1, 2, 1), result.rows().get(0).get("x"));
    }

    @Test
    public void bigintSequenceKeepsExactConversionAndRejectsFractions() {
        String config = CONFIG.replace("\"type\":\"INT\"", "\"type\":\"BIGINT\"");
        var engine = FeatureDagEngine.init(config, InitOptions.offline("bigint"));
        Map<String, List<?>> input = row();
        input.put("x", List.of(1L, 2L, 1L));
        assertEquals(List.of(1L, 1L), engine.generate(OfflineGenerateRequest.borrowed("longs", input)).featureValues().get("picked"));
        input.put("x", List.of(1L, 2, 1.0));
        assertEquals(List.of(1L, 1L), engine.generate(OfflineGenerateRequest.borrowed("mixed", input)).featureValues().get("picked"));
        input.put("x", List.of(1L, 2.5));
        assertThrows(FeatureGenerationException.class, () -> engine.generate(OfflineGenerateRequest.borrowed("invalid", input)));
    }
}
