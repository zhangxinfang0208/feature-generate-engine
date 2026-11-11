package com.example.featuredag.api;

import com.example.featuredag.performance.LargeOnlineWorkload;
import com.example.featuredag.physical.ExecutionEnvironment;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class OnlineColumnarOutputTest {
    @Test public void largeWorkloadMatchesRowsAndFreezesResults() throws Exception {
        var workload = new LargeOnlineWorkload(512, 300, 8);
        var engine = workload.engine(false);
        for (int r = 0; r < 8; r++) {
            var raw = workload.request(r);
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 300; i++) ids.add("item_" + i);
            Map<String,List<?>> columns = new LinkedHashMap<>();
            for (String name : engine.requiredCandidateInputNames()) {
                List<Object> values = new ArrayList<>();
                for (var row : raw.candidates()) values.add(row.get(name).getFirst());
                columns.put(name, values);
            }
            var result = engine.generateColumnarResult(OnlineColumnarGenerateRequest.borrowed(
                    "test", raw.sharedValues(), ids, columns));
            var rows = engine.generate(raw);
            assertEquals(rows.featureValues(), result.sharedFeatureValues());
            assertEquals(ids, result.candidateIds());
            for (int i = 0; i < 300; i++) {
                assertEquals(rows.candidateFeatureValues().get(i).keySet(), result.candidateFeatureColumns().keySet());
                for (var e : result.candidateFeatureColumns().entrySet()) {
                    Object cell = e.getValue().get(i);
                    assertEquals(rows.candidateFeatureValues().get(i).get(e.getKey()),
                            cell instanceof List<?> ? cell : Collections.singletonList(cell));
                }
            }
            ids.set(0, "changed");
            assertEquals("item_0", result.candidateIds().getFirst());
            assertThrows(UnsupportedOperationException.class, () -> result.candidateFeatureColumns().clear());
            assertThrows(UnsupportedOperationException.class, () -> result.candidateFeatureColumns().values().iterator().next().clear());
        }
    }

    @Test public void sequenceTruncationAndShortLengthsStoreNamesAndEmptyCandidates() {
        String config = """
            {"feature_set_name":"output","version":"1","features":[
            {"name":"x","definition_type":"BASE","type":"INT","value_shape":"SEQUENCE","entity_scopes":["ITEM"]},
            {"name":"y","store_name":"out","definition_type":"DERIVED","type":"INT","value_shape":"SEQUENCE","expression":"x","seq_max_length":3,"dft":-1}]}
            """;
        var engine = FeatureDagEngine.init(config, InitOptions.builder().environment(ExecutionEnvironment.ONLINE).build());
        var result = engine.generateColumnarResult(OnlineColumnarGenerateRequest.borrowed(
                "test", Map.of(), List.of("a","b"), Map.of("x", List.of(List.of(1), List.of(2,3,4,5)))));
        assertEquals(List.of(List.of(1), List.of(2,3,4)), result.candidateFeatureColumns().get("out"));
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>)result.candidateFeatureColumns().get("out").get(0)).clear());
        var empty = engine.generateColumnarResult(OnlineColumnarGenerateRequest.borrowed("empty", Map.of(), List.of(), Map.of()));
        assertTrue(empty.candidateIds().isEmpty());
        assertEquals(List.of(), empty.candidateFeatureColumns().get("out"));
    }
}
