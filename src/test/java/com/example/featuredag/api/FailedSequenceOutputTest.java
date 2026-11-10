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
                   "dft":%d,"seq_max_length":4,"output_policy":"OUTPUT"},
                  {"name":"selected_length","type":"INT","definition_type":"DERIVED",
                   "value_shape":"SCALAR","expression":"get_seq_length(selected)","output_policy":"OUTPUT"}]}
                """.formatted(defaultValue);
        return FeatureDagEngine.init(config, InitOptions.offline("slice"));
    }

    private Map<String, List<?>> row(Integer... indices) {
        return Map.of("values", List.of(0), "indices", List.of(indices));
    }

    @Test
    public void singleAndBatchReturnActualLengthAfterFallback() {
        FeatureDagEngine engine = engine(0);
        assertEquals(List.of(0), engine.generate(new OfflineGenerateRequest("bad", row(1)))
                .featureValues().get("selected"));
        var rows = engine.generateBatch(new OfflineBatchGenerateRequest("mixed",
                List.of(row(0), row(1), row()))).rows();
        assertEquals(List.of(0), rows.get(0).get("selected"));
        assertEquals(List.of(0), rows.get(1).get("selected"));
        assertEquals(List.of(0), rows.get(2).get("selected"));
    }

    @Test
    public void downstreamLengthUsesUnpaddedSequenceForFailedAndSuccessfulSelections() {
        FeatureDagEngine engine = engine(0);

        var failed = engine.generate(new OfflineGenerateRequest("out-of-bounds", row(1))).featureValues();
        assertEquals(List.of(0), failed.get("selected"));
        assertEquals(List.of(1), failed.get("selected_length"));

        var successful = engine.generate(new OfflineGenerateRequest("valid-index", row(0))).featureValues();
        assertEquals(List.of(0), successful.get("selected"));
        assertEquals(List.of(1), successful.get("selected_length"));
    }

    @Test
    public void nonzeroFailureDefaultReturnsOneElement() {
        assertEquals(List.of(9), engine(9)
                .generate(new OfflineGenerateRequest("bad", row(1))).featureValues().get("selected"));
    }
}
