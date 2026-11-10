package com.example.featuredag.api;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public final class FailedSequenceOutputTest {
    private FeatureDagEngine engine(int defaultValue) {
        String config = """
                {"feature_set_name":"slice","version":"1","features":[
                  {"name":"values","raw_name":"values","type":"INT","definition_type":"BASE",
                   "value_shape":"SEQUENCE","entity_scopes":["USER"]},
                  {"name":"indices","raw_name":"indices","type":"INT","definition_type":"BASE",
                   "value_shape":"SEQUENCE","entity_scopes":["USER"]},
                  {"name":"selected","store_name":"selected","type":"INT","definition_type":"DERIVED",
                   "value_shape":"SEQUENCE","expression":"slice_by_indices(values, indices)",
                   "dft":%d,"seq_max_length":4,"output_policy":"OUTPUT"}]}
                """.formatted(defaultValue);
        return FeatureDagEngine.init(config, InitOptions.offline("slice"));
    }

    private Map<String, List<?>> row(Integer... indices) {
        return Map.of("values", List.of(0), "indices", List.of(indices));
    }

    @Test
    public void zeroDefaultOnFailureSkipsPaddingInSingleAndBatch() {
        FeatureDagEngine engine = engine(0);
        assertEquals(List.of(0), engine.generate(new OfflineGenerateRequest("bad", row(1)))
                .featureValues().get("selected"));
        var rows = engine.generateBatch(new OfflineBatchGenerateRequest("mixed",
                List.of(row(0), row(1), row()))).rows();
        assertEquals(List.of(0, 0, 0, 0), rows.get(0).get("selected"));
        assertEquals(List.of(0), rows.get(1).get("selected"));
        assertEquals(List.of(0, 0, 0, 0), rows.get(2).get("selected"));
    }

    @Test
    public void nonzeroFailureDefaultStillPads() {
        assertEquals(List.of(9, 9, 9, 9), engine(9)
                .generate(new OfflineGenerateRequest("bad", row(1))).featureValues().get("selected"));
    }
}
