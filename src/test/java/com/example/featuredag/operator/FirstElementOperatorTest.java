package com.example.featuredag.operator;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OfflineGenerateRequest;
import com.example.featuredag.physical.ExecutionEnvironment;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public final class FirstElementOperatorTest {
    private final OperatorRegistry registry = OperatorRegistry.standard();

    @Test
    public void registersPureUnaryOperatorWithScalarBatchAdapter() {
        OperatorDefinition operator = registry.require("first_element");
        assertTrue(operator.deterministic());
        assertTrue(operator.sideEffectFree());
        assertTrue(operator.supportsSequenceView());
        assertEquals(BatchKernelKind.SCALAR_ADAPTER, registry.batchKernelKind("first_element"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.evaluate("first_element", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> registry.evaluate("first_element", List.of(List.of(1), List.of(2))));
    }

    @Test
    public void returnsFirstElementWithoutChangingItsCarrierOrInput() {
        for (Object first : List.of("A", 7, 1791281209L, 1.5, true)) {
            List<Object> sequence = Arrays.asList(first, "unused");
            assertSame(first, evaluate(sequence));
            assertEquals(Arrays.asList(first, "unused"), sequence);
        }
        assertNull(evaluate(Arrays.asList(null, "B")));
    }

    @Test
    public void infersScalarWithOriginalElementTypeAndScope() {
        for (DataType type : List.of(DataType.STRING, DataType.INT, DataType.BIGINT,
                DataType.DOUBLE, DataType.BOOLEAN)) {
            OperatorInference result = registry.infer("first_element",
                    List.of(new Input(type, ValueShape.SEQUENCE)));
            assertEquals(type, result.outputType());
            assertEquals(ValueShape.SCALAR, result.valueShape());
            assertEquals(Set.of(EntityScope.USER), result.entityScopes());
        }
        for (Input invalid : List.of(new Input(DataType.STRING, ValueShape.SCALAR),
                new Input(DataType.INT, ValueShape.CANDIDATE_VECTOR),
                new Input(DataType.EVENT_SEQUENCE, ValueShape.SEQUENCE))) {
            assertThrows(IllegalArgumentException.class,
                    () -> registry.infer("first_element", List.of(invalid)));
        }
    }

    @Test
    public void rejectsEmptyNullAndNonSequenceValues() {
        for (Object invalid : Arrays.asList(List.of(), null, "A", 3,
                List.of(Map.of("key", "value")), List.of(List.of(1)))) {
            assertThrows(IllegalArgumentException.class, () -> evaluate(invalid));
        }
        assertEquals(DataType.UNKNOWN, registry.infer("first_element",
                List.of(new Input(DataType.OBJECT, ValueShape.SEQUENCE))).outputType());
    }

    @Test
    public void readsOnlyFirstLogicalViewElementAndPreservesBatchOrder() {
        OperatorSequence view = new OperatorSequence() {
            public int size() { return 100; }
            public Object elementAt(int index) {
                assertEquals(0, index);
                return "selected-first";
            }
            public OperatorSequence filterByColumn(String column, Object value) {
                throw new UnsupportedOperationException();
            }
        };
        assertEquals("selected-first", evaluate(view));
        BatchLayout layout = new BatchLayout() {
            public BatchDomain domain() { return BatchDomain.OFFLINE_ROW; }
            public int rowCount() { return 3; }
            public int groupIndexAt(int row) { return -1; }
            public int indexInGroupAt(int row) { return row; }
        };
        BatchOperatorResult result = registry.evaluateBatch("first_element", new BatchOperatorCall(
                layout, List.of(new ListBatchColumn(List.of(view, List.of("B", "C"), List.of("D"))))));
        assertEquals(3, result.values().size());
        assertEquals("selected-first", result.values().valueAt(0));
        assertEquals("B", result.values().valueAt(1));
        assertEquals("D", result.values().valueAt(2));
        BatchOperatorEvaluationException failure = assertThrows(BatchOperatorEvaluationException.class,
                () -> registry.evaluateBatch("first_element", new BatchOperatorCall(layout,
                        List.of(new ListBatchColumn(List.of(List.of("A"), List.of(), List.of("C")))))));
        assertEquals(1, failure.rowIndex());
    }

    @Test
    public void executesLiteralNamedSequenceAndNestedSelectionThroughPublicApi() {
        String config = """
                {"feature_set_name":"first-element","version":"1","features":[
                  {"name":"auid_hwdsp_clk_ts_seq_time_365d","type":"BIGINT","definition_type":"BASE",
                   "entity_scopes":["USER"],"value_shape":"SEQUENCE"},
                  {"name":"first_ts","type":"BIGINT","definition_type":"DERIVED",
                   "expression":"first_element(auid_hwdsp_clk_ts_seq_time_365d)","dft":-1},
                  {"name":"first_string","type":"STRING","definition_type":"DERIVED",
                   "expression":"first_element(['A', 'B'])"},
                  {"name":"selected_ts","type":"BIGINT","definition_type":"DERIVED",
                   "expression":"first_element(slice_by_indices(auid_hwdsp_clk_ts_seq_time_365d, [1, 0]))","dft":-1}
                ]}
                """;
        FeatureDagEngine engine = FeatureDagEngine.init(config,
                InitOptions.builder().environment(ExecutionEnvironment.OFFLINE).build());
        Map<String, List<?>> values = engine.generate(new OfflineGenerateRequest("row",
                Map.of("auid_hwdsp_clk_ts_seq_time_365d", List.of(1791281209L, 1791281000L)))).featureValues();
        assertEquals(List.of("A"), values.get("first_string"));
        assertEquals(List.of(1791281209L), values.get("first_ts"));
        assertEquals(List.of(1791281000L), values.get("selected_ts"));
        assertEquals(List.of(-1L), engine.generate(new OfflineGenerateRequest("empty",
                Map.of("auid_hwdsp_clk_ts_seq_time_365d", List.of()))).featureValues().get("first_ts"));
    }

    private Object evaluate(Object sequence) {
        return registry.evaluate("first_element", Collections.singletonList(sequence));
    }

    private record Input(DataType outputType, ValueShape valueShape) implements OperatorInputMetadata {
        public Set<EntityScope> entityScopes() { return Set.of(EntityScope.USER); }
        public String sourceFeatureName() { return null; }
    }
}
