package com.example.featuredag.operator;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.builtin.InitialBusinessOperators;
import com.example.featuredag.operator.builtin.IntersectionOperator;
import org.junit.Test;
import com.example.featuredag.api.*;
import com.example.featuredag.physical.ExecutionEnvironment;
import java.util.*;
import static org.junit.Assert.*;

public final class IntersectionOperatorTest {
    private final OperatorRegistry registry = OperatorRegistry.standard();

    @Test public void registersIndependentPureOperatorWithScalarAdapter() {
        assertEquals(24, InitialBusinessOperators.definitions().size());
        assertTrue(registry.require("intersection") instanceof IntersectionOperator);
        assertTrue(registry.require("intersection").deterministic());
        assertTrue(registry.require("intersection").sideEffectFree());
        assertTrue(registry.require("intersection").supportsSequenceView());
        assertEquals(BatchKernelKind.SCALAR_ADAPTER, registry.batchKernelKind("intersection"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.evaluate("intersection", List.of(List.of(1))));
        assertThrows(IllegalArgumentException.class,
                () -> registry.evaluate("intersection", List.of(List.of(1), List.of(1), List.of(1))));
    }

    @Test public void preservesLeftOrderDeduplicatesAndDoesNotModifyInputs() {
        List<String> left = Arrays.asList("b", "a", "b", "c");
        List<String> right = Arrays.asList("a", "b", "b", "d");
        Object result = intersection(left, right);
        assertEquals(List.of("b", "a"), result);
        assertEquals(Arrays.asList("b", "a", "b", "c"), left);
        assertEquals(Arrays.asList("a", "b", "b", "d"), right);
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) result).clear());
        assertEquals(List.of(), intersection(List.of(), right));
        assertEquals(List.of(), intersection(left, List.of()));
        assertEquals(List.of(), intersection(List.of("x"), right));
        assertEquals(Arrays.asList(null, true), intersection(
                Arrays.asList(null, true, null, false), Arrays.asList(true, null)));
    }

    @Test public void comparesNumbersPreciselyAndRetainsLeftCarriers() {
        assertEquals(List.of(2, 1), intersection(List.of(2, 1, 2), List.of(1L, 2.0)));
        assertEquals(List.of(1L), intersection(List.of(1L), List.of(1.0)));
        assertEquals(List.of(), intersection(List.of(9007199254740993L), List.of(9007199254740992.0)));
        assertEquals(List.of(-0.0), intersection(List.of(-0.0, 0.0), List.of(0)));
    }

    @Test public void infersLeftTypeSequenceAndUnionOfScopes() {
        OperatorInference result = registry.infer("intersection", List.of(
                new Input(DataType.INT, ValueShape.SEQUENCE, EntityScope.USER),
                new Input(DataType.DOUBLE, ValueShape.SEQUENCE, EntityScope.ITEM)));
        assertEquals(DataType.INT, result.outputType());
        assertEquals(ValueShape.SEQUENCE, result.valueShape());
        assertEquals(Set.of(EntityScope.USER, EntityScope.ITEM), result.entityScopes());
        for (DataType type : List.of(DataType.INT, DataType.BIGINT, DataType.DOUBLE, DataType.STRING, DataType.BOOLEAN)) {
            Input input = new Input(type, ValueShape.SEQUENCE, EntityScope.USER);
            assertEquals(type, registry.infer("intersection", List.of(input, input)).outputType());
        }
    }

    @Test public void rejectsInvalidInferenceAndRuntimeValuesEvenWithEmptyOtherSide() {
        Input strings = new Input(DataType.STRING, ValueShape.SEQUENCE, EntityScope.USER);
        for (Input bad : List.of(new Input(DataType.INT, ValueShape.SCALAR, EntityScope.USER),
                new Input(DataType.EVENT_SEQUENCE, ValueShape.SEQUENCE, EntityScope.USER),
                new Input(DataType.OBJECT, ValueShape.SEQUENCE, EntityScope.USER),
                new Input(DataType.INT, ValueShape.SEQUENCE, EntityScope.USER))) {
            assertThrows(IllegalArgumentException.class, () -> registry.infer("intersection", List.of(strings, bad)));
        }
        for (Object bad : Arrays.asList(null, "scalar", List.of(Map.of("key", "a")),
                List.of(List.of(1)), List.of(Double.NaN), List.of(Double.POSITIVE_INFINITY), List.of("a", 1))) {
            assertThrows(IllegalArgumentException.class, () -> intersection(bad, List.of()));
            assertThrows(IllegalArgumentException.class, () -> intersection(List.of(), bad));
        }
        assertThrows(IllegalArgumentException.class, () -> intersection(List.of("1"), List.of(1)));
    }

    @Test public void consumesViewsAndBatchMatchesEachSingleRow() {
        OperatorSequence view = new OperatorSequence() {
            public int size() { return 3; }
            public Object elementAt(int index) { return Arrays.asList("b", "a", "b").get(index); }
            public OperatorSequence filterByColumn(String column, Object value) { throw new UnsupportedOperationException(); }
        };
        assertEquals(List.of("b", "a"), intersection(view, view));
        List<Object> left = Arrays.asList(view, List.of(), List.of("x", "a"));
        List<Object> right = Arrays.asList(List.of("a", "b"), view, List.of("a"));
        BatchLayout layout = new BatchLayout() {
            public BatchDomain domain() { return BatchDomain.OFFLINE_ROW; }
            public int rowCount() { return 3; }
            public int groupIndexAt(int index) { return -1; }
            public int indexInGroupAt(int index) { return index; }
        };
        BatchOperatorResult result = registry.evaluateBatch("intersection", new BatchOperatorCall(
                layout, List.of(new ListBatchColumn(left), new ListBatchColumn(right))));
        for (int row = 0; row < 3; row++) assertEquals(intersection(left.get(row), right.get(row)), result.values().valueAt(row));
    }

    @Test
    public void executesIntersectionThroughPublicApi() {
        String configJson = "{"
                + "\"feature_set_name\":\"intersection-feature\","
                + "\"version\":\"1\","
                + "\"features\":["
                + "{\"name\":\"left_values\",\"raw_name\":\"left_values\","
                + "\"type\":\"STRING\",\"definition_type\":\"BASE\","
                + "\"entity_scopes\":[\"USER\"],\"value_shape\":\"SEQUENCE\"},"
                + "{\"name\":\"right_values\",\"raw_name\":\"right_values\","
                + "\"type\":\"STRING\",\"definition_type\":\"BASE\","
                + "\"entity_scopes\":[\"USER\"],\"value_shape\":\"SEQUENCE\"},"
                + "{\"name\":\"combined_values\",\"type\":\"STRING\","
                + "\"definition_type\":\"DERIVED\","
                + "\"expression\":\"intersection(left_values, right_values)\","
                + "\"output_policy\":\"OUTPUT\",\"entity_scopes\":[\"USER\"],"
                + "\"value_shape\":\"SEQUENCE\"}]}";
        FeatureDagEngine engine = FeatureDagEngine.init(
                configJson,
                InitOptions.builder().environment(ExecutionEnvironment.OFFLINE).build());
        Map<String, List<?>> inputs = new LinkedHashMap<String, List<?>>();
        inputs.put("left_values", Arrays.asList("a", "b"));
        inputs.put("right_values", Collections.singletonList("b"));

        GenerateResult result = engine.generate(
                new OfflineGenerateRequest("intersection-row", inputs));

        assertEquals(
                Collections.singletonList("b"),
                result.featureValues().get("combined_values"));
    }

    private Object intersection(Object left, Object right) {
        return registry.evaluate("intersection", Arrays.asList(left, right));
    }

    private record Input(DataType outputType, ValueShape valueShape, EntityScope scope) implements OperatorInputMetadata {
        public Set<EntityScope> entityScopes() { return Set.of(scope); }
        public String sourceFeatureName() { return null; }
    }
}
