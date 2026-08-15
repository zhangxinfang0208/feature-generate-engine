package com.example.featuredag.operator;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.GenerateResult;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OfflineGenerateRequest;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** calc_delta_seq 可选方向、单位换算、Native Batch 与表达式端到端测试（JUnit 4）。 */
public final class CalculateDeltaSequenceConfigurationTest {
    private static final long REQUEST_TIMESTAMP_MS = 1_720_007_200_000L;

    @Test
    public void legacyTwoArgumentCallKeepsElementMinusBase() {
        OperatorRegistry registry = OperatorRegistry.standard();

        assertEquals(2, registry.require("calc_delta_seq").minArguments());
        assertEquals(3, registry.require("calc_delta_seq").maxArguments());
        assertEquals(
                Arrays.asList(-8.0, -5.0, -1.0),
                registry.evaluate(
                        "calc_delta_seq",
                        Arrays.<Object>asList(Arrays.asList(2, 5, 9), 10)));
    }

    @Test
    public void baseMinusElementConvertsMillisecondsToHours() {
        OperatorRegistry registry = OperatorRegistry.standard();
        List<Long> timestamps = Arrays.asList(
                REQUEST_TIMESTAMP_MS - 3_600_000L,
                REQUEST_TIMESTAMP_MS - 10_800_000L);

        assertEquals(
                Arrays.asList(1.0, 3.0),
                registry.evaluate(
                        "calc_delta_seq",
                        Arrays.<Object>asList(
                                timestamps,
                                REQUEST_TIMESTAMP_MS,
                                config("BASE_MINUS_ELEMENT", 3_600_000))));
    }

    @Test
    public void nativeBatchSeparatesDirectionAndDivisorInReuseKey() {
        OperatorRegistry registry = OperatorRegistry.standard();
        List<Long> timestamps = Arrays.asList(REQUEST_TIMESTAMP_MS - 7_200_000L);
        BatchOperatorCall call = new BatchOperatorCall(
                new FixedBatchLayout(BatchDomain.OFFLINE_ROW, 3),
                Arrays.<BatchColumn>asList(
                        new ListBatchColumn(Arrays.asList(timestamps, timestamps, timestamps)),
                        new ListBatchColumn(Arrays.asList(
                                REQUEST_TIMESTAMP_MS,
                                REQUEST_TIMESTAMP_MS,
                                REQUEST_TIMESTAMP_MS)),
                        new ListBatchColumn(Arrays.asList(
                                config("BASE_MINUS_ELEMENT", 3_600_000),
                                config("BASE_MINUS_ELEMENT", 60_000),
                                config("ELEMENT_MINUS_BASE", 3_600_000)))));

        BatchOperatorResult result = registry.evaluateBatch(
                "calc_delta_seq", call, BatchKernelKind.NATIVE);

        assertEquals(Arrays.asList(2.0), result.values().valueAt(0));
        assertEquals(Arrays.asList(120.0), result.values().valueAt(1));
        assertEquals(Arrays.asList(-2.0), result.values().valueAt(2));
    }

    @Test
    public void rejectsInvalidConfiguration() {
        OperatorRegistry registry = OperatorRegistry.standard();
        List<Long> timestamps = Arrays.asList(REQUEST_TIMESTAMP_MS - 3_600_000L);

        assertFailureContains(
                registry, timestamps, config("UNKNOWN", 3_600_000), "direction");
        assertFailureContains(
                registry, timestamps, config("BASE_MINUS_ELEMENT", 0), "greater than 0");
        assertFailureContains(
                registry, timestamps, config("BASE_MINUS_ELEMENT", -1), "greater than 0");
        assertFailureContains(
                registry, timestamps, config("BASE_MINUS_ELEMENT", Double.NaN), "finite");

        Map<String, Object> unknownKey = config("BASE_MINUS_ELEMENT", 3_600_000);
        unknownKey.put("unit", "HOUR");
        assertFailureContains(registry, timestamps, unknownKey, "unknown key");
    }

    @Test
    public void nativeBatchReportsInvalidConfigRow() {
        OperatorRegistry registry = OperatorRegistry.standard();
        List<Long> timestamps = Arrays.asList(REQUEST_TIMESTAMP_MS - 3_600_000L);
        BatchOperatorCall call = new BatchOperatorCall(
                new FixedBatchLayout(BatchDomain.OFFLINE_ROW, 2),
                Arrays.<BatchColumn>asList(
                        new ListBatchColumn(Arrays.asList(timestamps, timestamps)),
                        new ListBatchColumn(Arrays.asList(
                                REQUEST_TIMESTAMP_MS, REQUEST_TIMESTAMP_MS)),
                        new ListBatchColumn(Arrays.asList(
                                config("BASE_MINUS_ELEMENT", 3_600_000),
                                config("BASE_MINUS_ELEMENT", 0)))));

        BatchOperatorEvaluationException failure = assertThrows(
                BatchOperatorEvaluationException.class,
                () -> registry.evaluateBatch(
                        "calc_delta_seq", call, BatchKernelKind.NATIVE));

        assertEquals(1, failure.rowIndex());
        assertTrue(failure.getMessage(), failure.getMessage().contains("greater than 0"));
    }

    @Test
    public void expressionConfigCalculatesTimegapHoursEndToEnd() {
        String configJson = "{"
                + "\"feature_set_name\":\"timegap-example\","
                + "\"version\":\"1\","
                + "\"features\":["
                + "{\"name\":\"behavior_timestamp_ms\","
                + "\"raw_name\":\"behavior_timestamp_ms\","
                + "\"type\":\"DOUBLE\",\"definition_type\":\"BASE\","
                + "\"entity_scopes\":[\"USER\"],\"value_shape\":\"SEQUENCE\"},"
                + "{\"name\":\"request_timestamp_ms\","
                + "\"raw_name\":\"request_timestamp_ms\","
                + "\"type\":\"DOUBLE\",\"definition_type\":\"BASE\","
                + "\"entity_scopes\":[\"USER\"],\"value_shape\":\"SCALAR\"},"
                + "{\"name\":\"timegap_hours\",\"type\":\"DOUBLE\","
                + "\"definition_type\":\"DERIVED\","
                + "\"expression\":\"calc_delta_seq(behavior_timestamp_ms, "
                + "request_timestamp_ms, {\\\"direction\\\":\\\"BASE_MINUS_ELEMENT\\\","
                + "\\\"divisor\\\":3600000})\","
                + "\"output_policy\":\"OUTPUT\",\"entity_scopes\":[\"USER\"],"
                + "\"value_shape\":\"SEQUENCE\"}]}";
        FeatureDagEngine engine = FeatureDagEngine.init(
                configJson, InitOptions.offline("timegap-example-plan"));
        Map<String, List<?>> inputs = new LinkedHashMap<String, List<?>>();
        inputs.put("behavior_timestamp_ms", Arrays.asList(
                REQUEST_TIMESTAMP_MS - 3_600_000L,
                REQUEST_TIMESTAMP_MS - 10_800_000L));
        inputs.put("request_timestamp_ms", Arrays.asList(REQUEST_TIMESTAMP_MS));

        GenerateResult result = engine.generate(
                new OfflineGenerateRequest("timegap-example-row", inputs));

        assertEquals(Arrays.asList(1.0, 3.0), result.featureValues().get("timegap_hours"));
    }

    private static Map<String, Object> config(String direction, Object divisor) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("direction", direction);
        result.put("divisor", divisor);
        return result;
    }

    private static void assertFailureContains(
            OperatorRegistry registry,
            List<Long> timestamps,
            Map<String, Object> config,
            String messagePart) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate(
                        "calc_delta_seq",
                        Arrays.<Object>asList(timestamps, REQUEST_TIMESTAMP_MS, config)));
        assertTrue(failure.getMessage(), failure.getMessage().contains(messagePart));
    }

    private static final class FixedBatchLayout implements BatchLayout {
        private final BatchDomain domain;
        private final int rowCount;

        private FixedBatchLayout(BatchDomain domain, int rowCount) {
            this.domain = domain;
            this.rowCount = rowCount;
        }

        @Override
        public BatchDomain domain() {
            return domain;
        }

        @Override
        public int rowCount() {
            return rowCount;
        }

        @Override
        public int groupIndexAt(int rowIndex) {
            return domain == BatchDomain.ONLINE_CANDIDATE ? rowIndex / 2 : -1;
        }

        @Override
        public int indexInGroupAt(int rowIndex) {
            return domain == BatchDomain.ONLINE_CANDIDATE ? rowIndex % 2 : rowIndex;
        }
    }
}
