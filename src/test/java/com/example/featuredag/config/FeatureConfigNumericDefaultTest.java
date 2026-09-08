package com.example.featuredag.config;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OfflineBatchGenerateRequest;
import com.example.featuredag.api.OfflineGenerateRequest;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class FeatureConfigNumericDefaultTest {
    @Test
    public void nonZeroNumericStringPreservesDefaultValue() {
        assertDefaults("INT", "\"2\"", 2);
        assertDefaults("BIGINT", "\"2\"", 2L);
        assertDefaults("DOUBLE", "\"2\"", 2.0);
        for (String type : new String[] {"INT", "BIGINT", "DOUBLE"}) {
            FeatureDagEngine engine = FeatureDagEngine.init(
                    config(type, "\"2\""), InitOptions.offline("nonzero-default"));
            Object actual = engine.generate(new OfflineGenerateRequest(
                    "missing", Collections.<String, List<?>>emptyMap()))
                    .featureValues().get("output").get(0);
            assertEquals(2.0, ((Number) actual).doubleValue(), 0.0);
        }
    }

    @Test
    public void numericStringsAndBlanksNormalizeForBaseAndDerived() {
        for (String dft : List.of("\"0\"", "\"\"", "\"  \"", "0")) {
            assertDefaults("INT", dft, 0);
            assertDefaults("BIGINT", dft, 0L);
            assertDefaults("DOUBLE", dft, 0.0);
        }
        assertDefaults("INT", "\" -2147483648 \"", Integer.MIN_VALUE);
        assertDefaults("INT", "\"2147483647\"", Integer.MAX_VALUE);
        assertDefaults("INT", "\"1.0\"", 1);
        assertDefaults("BIGINT", "\"9223372036854775807\"", Long.MAX_VALUE);
        assertDefaults("BIGINT", "\"-9223372036854775808\"", Long.MIN_VALUE);
        assertDefaults("DOUBLE", "\" -1.25e2 \"", -125.0);
    }

    @Test
    public void invalidNumericStringsRetainFeatureAndTypeDiagnostics() {
        for (String type : List.of("INT", "BIGINT", "DOUBLE")) {
            for (String value : List.of("abc", "null", "NaN", "Infinity")) {
                assertInvalid(type, value);
            }
        }
        for (String value : List.of("1.5", "2147483648", "-2147483649", "1.00000000000000000001")) {
            assertInvalid("INT", value);
        }
        for (String value : List.of("1.5", "9223372036854775808", "-9223372036854775809")) {
            assertInvalid("BIGINT", value);
        }
        assertInvalid("DOUBLE", "1e309");
    }

    @Test
    public void nullMissingAndNonNumericTypesKeepExistingMeaning() {
        for (String type : List.of("INT", "BIGINT", "DOUBLE")) {
            assertDefaults(type, "null", null);
            MappedFeatureSet missing = map(config(type, "null").replace(",\"dft\":null", ""));
            missing.definitions().forEach(definition -> assertNull(definition.defaultValue()));
        }
        assertDefaults("STRING", "\"\"", "");
        assertDefaults("STRING", "\"0\"", "0");
        assertDefaults("BOOLEAN", "true", true);
        assertThrows(IllegalArgumentException.class, () -> map(config("BOOLEAN", "\"\"")));
    }

    @Test
    public void publicApiUsesTypedDefaultsInSingleBatchAndSequencePadding() {
        for (String type : List.of("INT", "BIGINT", "DOUBLE")) {
            Object zero = switch (type) {
                case "INT" -> Integer.valueOf(0);
                case "BIGINT" -> Long.valueOf(0);
                default -> Double.valueOf(0);
            };
            String json = config(type, "\"\"");
            FeatureDagEngine scalar = FeatureDagEngine.init(json, InitOptions.offline("numeric-dft"));
            assertEquals(List.of(zero), scalar.generate(new OfflineGenerateRequest(
                    "missing", Map.of())).featureValues().get("output"));
            Map<String, List<?>> presentRow = Map.of("source", List.of(7));
            var batch = scalar.generateBatch(new OfflineBatchGenerateRequest("batch", List.of(Map.of(), presentRow)));
            assertEquals(List.of(zero), batch.rows().get(0).get("output"));
            assertEquals(7.0, ((Number) batch.rows().get(1).get("output").get(0)).doubleValue(), 0.0);

            FeatureDagEngine sequence = FeatureDagEngine.init(
                    json.replace("SCALAR", "SEQUENCE").replace(
                            "\"expression\":\"source\"", "\"expression\":\"source\",\"seq_max_length\":3"),
                    InitOptions.offline("numeric-sequence-dft"));
            assertEquals(Collections.nCopies(3, zero), sequence.generate(new OfflineGenerateRequest(
                    "empty", Map.of("source", List.of()))).featureValues().get("output"));
        }
    }

    private static void assertDefaults(String type, String dft, Object expected) {
        map(config(type, dft)).definitions().forEach(definition ->
                assertEquals(type + " " + dft + " " + definition.name(), expected, definition.defaultValue()));
    }

    private static void assertInvalid(String type, String value) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> map(config(type, "\"" + value + "\"")));
        assertTrue(error.getMessage().contains("source"));
        assertTrue(error.getMessage().contains(type));
    }

    private static MappedFeatureSet map(String json) {
        return FeatureConfigMapper.map(FeatureConfigLoader.load(json), Set.of(), Map.of());
    }

    private static String config(String type, String dft) {
        return """
                {"feature_set_name":"numeric-default","version":"1","features":[
                  {"name":"source","definition_type":"BASE","type":"%s",
                   "value_shape":"SCALAR","dft":%s},
                  {"name":"output","definition_type":"DERIVED","type":"%s",
                   "value_shape":"SCALAR","expression":"source","dft":%s}
                ]}
                """.formatted(type, dft, type, dft);
    }
}
