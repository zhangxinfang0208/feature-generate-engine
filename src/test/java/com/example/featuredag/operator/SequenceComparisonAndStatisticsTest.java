package com.example.featuredag.operator;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OfflineGenerateRequest;
import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.builtin.*;
import com.example.featuredag.physical.ExecutionEnvironment;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public final class SequenceComparisonAndStatisticsTest {
    private final OperatorRegistry registry = OperatorRegistry.standard();
    private static final List<Integer> TIMES = List.of(1, 3, 5, 7, 9, 12, 19);
    private static final List<String> NAMES = List.of(
            "compare_smaller", "compare_greater", "concat_seq", "calc_seq_sub", "mean");

    @Test
    public void registersFiveIndependentPureOperatorsWithScalarBatchAdapters() {
        assertEquals(31, InitialBusinessOperators.definitions().size());
        List<Class<?>> classes = List.of(CompareSmallerOperator.class, CompareGreaterOperator.class,
                ConcatSequenceOperator.class, CalculateSequenceSubOperator.class, MeanOperator.class);
        for (int i = 0; i < NAMES.size(); i++) {
            String name = NAMES.get(i);
            OperatorDefinition operator = registry.require(name);
            assertEquals(classes.get(i), operator.getClass());
            assertTrue(operator.deterministic());
            assertTrue(operator.sideEffectFree());
            assertTrue(operator.supportsSequenceView());
            int arity = i < 3 ? 2 : 1;
            assertEquals(arity, operator.minArguments());
            assertEquals(arity, operator.maxArguments());
            assertEquals(BatchKernelKind.SCALAR_ADAPTER, registry.batchKernelKind(name));
            assertThrows(IllegalArgumentException.class, () -> registry.evaluate(name, List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> registry.evaluate(name, Collections.nCopies(arity + 1, TIMES)));
        }
    }

    @Test
    public void reproducesRequestedExamplesAndPreservesInputs() {
        assertEquals(List.of(0, 1, 2, 3), evaluate("compare_smaller", TIMES, 8));
        assertEquals(List.of(4, 5, 6), evaluate("compare_greater", TIMES, 8));
        List<String> left = new ArrayList<>(List.of("a", "b", "c"));
        List<String> right = new ArrayList<>(List.of("d", "e", "f", "a"));
        Object concatenated = evaluate("concat_seq", left, right);
        assertEquals(List.of("a", "b", "c", "d", "e", "f", "a"), concatenated);
        assertEquals(List.of("a", "b", "c"), left);
        assertEquals(List.of("d", "e", "f", "a"), right);
        left.clear();
        right.clear();
        assertEquals(7, ((List<?>) concatenated).size());
        Object differences = evaluate("calc_seq_sub", TIMES);
        assertEquals(List.of(2L, 2L, 2L, 2L, 3L, 7L), differences);
        assertEquals(3.0, evaluate("mean", differences));
        assertEquals(List.of(1, 3, 5, 7, 9, 12, 19), TIMES);
        for (Object output : List.of(concatenated, differences, evaluate("compare_smaller", TIMES, 8),
                evaluate("compare_greater", TIMES, 8))) {
            assertThrows(UnsupportedOperationException.class, () -> ((List<?>) output).clear());
        }
    }

    @Test
    public void comparesUnsortedRepeatedValuesStrictlyWithoutLosingBigintPrecision() {
        List<Number> values = List.of(9, 8L, -1, 8.0, 12, 3.5);
        assertEquals(List.of(2, 5), evaluate("compare_smaller", values, 8));
        assertEquals(List.of(0, 4), evaluate("compare_greater", values, 8));
        long boundary = 9007199254740992L;
        List<Long> big = List.of(boundary + 1, boundary, boundary - 1);
        assertEquals(List.of(0), evaluate("compare_greater", big, (double) boundary));
        assertEquals(List.of(2), evaluate("compare_smaller", big, (double) boundary));
        assertEquals(List.of(), evaluate("compare_greater", List.of(8, 8), 8));
        assertEquals(List.of(), evaluate("compare_smaller", List.of(), 8));
        assertEquals(List.of(), evaluate("compare_greater", List.of(), 8));
    }

    @Test
    public void computesSignedDifferencesAndFractionalMeansWithoutIntermediateOverflow() {
        assertEquals(List.of(-2L, 0L, 4L), evaluate("calc_seq_sub", List.of(5, 3, 3, 7)));
        assertEquals(List.of(0.2, -0.4), evaluate("calc_seq_sub", List.of(0.1, 0.3, -0.1)));
        assertEquals(List.of(1L), evaluate("calc_seq_sub", List.of(Long.MAX_VALUE - 1, Long.MAX_VALUE)));
        assertEquals(List.of(), evaluate("calc_seq_sub", List.of()));
        assertEquals(List.of(), evaluate("calc_seq_sub", List.of(2)));
        assertEquals(1.5, evaluate("mean", List.of(1, 2)));
        assertEquals(-2.5, evaluate("mean", List.of(-2, -3)));
        assertEquals(0.2, evaluate("mean", List.of(0.1, 0.3)));
        assertEquals(7.0, evaluate("mean", List.of(7)));
        assertEquals((double) Long.MAX_VALUE, evaluate("mean", List.of(Long.MAX_VALUE, Long.MAX_VALUE)));
        assertEquals(Double.MAX_VALUE, evaluate("mean", List.of(Double.MAX_VALUE, Double.MAX_VALUE)));
        assertEquals(0.0, evaluate("mean", List.of(Double.MAX_VALUE, -Double.MAX_VALUE)));
    }

    @Test
    public void concatenatesEmptyNumericAndBooleanSequencesAndRetainsNulls() {
        assertEquals(List.of(), evaluate("concat_seq", List.of(), List.of()));
        assertEquals(List.of(1, 1), evaluate("concat_seq", List.of(), List.of(1, 1)));
        assertEquals(List.of(1, 1), evaluate("concat_seq", List.of(1, 1), List.of()));
        assertEquals(List.of(1, 2L, 3.5), evaluate("concat_seq", List.of(1), List.of(2L, 3.5)));
        assertEquals(Arrays.asList(true, null, false),
                evaluate("concat_seq", Arrays.asList(true, null), List.of(false)));
    }

    @Test
    public void infersTypesShapesAndUnionOfScopes() {
        for (DataType type : List.of(DataType.INT, DataType.BIGINT, DataType.DOUBLE)) {
            Input seq = new Input(type, ValueShape.SEQUENCE);
            for (String name : List.of("compare_smaller", "compare_greater")) {
                OperatorInference result = registry.infer(name, List.of(seq,
                        new Input(DataType.DOUBLE, ValueShape.CANDIDATE_VECTOR, Set.of(EntityScope.ITEM))));
                assertInference(result, DataType.INT, ValueShape.SEQUENCE, Set.of(EntityScope.USER, EntityScope.ITEM));
            }
            assertInference(registry.infer("calc_seq_sub", List.of(seq)), type,
                    ValueShape.SEQUENCE, Set.of(EntityScope.USER));
            assertInference(registry.infer("mean", List.of(seq)), DataType.DOUBLE,
                    ValueShape.SCALAR, Set.of(EntityScope.USER));
        }
        for (DataType type : List.of(DataType.STRING, DataType.BOOLEAN, DataType.INT)) {
            assertEquals(type, registry.infer("concat_seq", List.of(new Input(type, ValueShape.SEQUENCE),
                    new Input(type, ValueShape.SEQUENCE))).outputType());
        }
        assertEquals(DataType.BIGINT, registry.infer("concat_seq", List.of(
                new Input(DataType.INT, ValueShape.SEQUENCE), new Input(DataType.BIGINT, ValueShape.SEQUENCE))).outputType());
        assertEquals(DataType.DOUBLE, registry.infer("concat_seq", List.of(
                new Input(DataType.BIGINT, ValueShape.SEQUENCE), new Input(DataType.DOUBLE, ValueShape.SEQUENCE))).outputType());
        assertEquals(DataType.UNKNOWN, registry.infer("calc_seq_sub",
                List.of(new Input(DataType.OBJECT, ValueShape.SEQUENCE))).outputType());
    }

    @Test
    public void rejectsInvalidMetadataAndArityDuringInference() {
        Input numeric = new Input(DataType.INT, ValueShape.SEQUENCE);
        Input scalar = new Input(DataType.INT, ValueShape.SCALAR);
        for (String name : NAMES) {
            assertThrows(IllegalArgumentException.class, () -> registry.infer(name, List.of()));
            for (Input invalid : List.of(scalar, new Input(DataType.INT, ValueShape.OBJECT),
                    new Input(DataType.INT, ValueShape.CANDIDATE_VECTOR),
                    new Input(DataType.EVENT_SEQUENCE, ValueShape.SEQUENCE))) {
                List<Input> inputs = name.startsWith("compare_") ? List.of(invalid, scalar)
                        : name.equals("concat_seq") ? List.of(invalid, numeric) : List.of(invalid);
                assertThrows(name, IllegalArgumentException.class, () -> registry.infer(name, inputs));
            }
        }
        for (String name : List.of("compare_smaller", "compare_greater", "calc_seq_sub", "mean")) {
            for (DataType type : List.of(DataType.STRING, DataType.BOOLEAN)) {
                Input invalid = new Input(type, ValueShape.SEQUENCE);
                assertThrows(IllegalArgumentException.class, () -> registry.infer(name,
                        name.startsWith("compare_") ? List.of(invalid, scalar) : List.of(invalid)));
            }
        }
        for (String name : List.of("compare_smaller", "compare_greater")) {
            for (Input threshold : List.of(numeric, new Input(DataType.STRING, ValueShape.SCALAR),
                    new Input(DataType.INT, ValueShape.INDEX))) {
                assertThrows(IllegalArgumentException.class, () -> registry.infer(name, List.of(numeric, threshold)));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> registry.infer("concat_seq", List.of(numeric, scalar)));
        assertThrows(IllegalArgumentException.class, () -> registry.infer("concat_seq", List.of(
                numeric, new Input(DataType.STRING, ValueShape.SEQUENCE))));
    }

    @Test
    public void rejectsInvalidRuntimeInputsAndReportsNumericElementPositions() {
        for (String name : NAMES) {
            for (Object invalid : Arrays.asList(null, 1, "1", Map.of("x", 1))) {
                assertThrows(name, IllegalArgumentException.class,
                        () -> evaluate(name, arguments(name, invalid, List.of(1))));
            }
        }
        for (String name : List.of("compare_smaller", "compare_greater", "calc_seq_sub", "mean")) {
            for (Object invalid : Arrays.asList(null, "2", true, Double.NaN, Double.POSITIVE_INFINITY,
                    Double.NEGATIVE_INFINITY, List.of(1), Map.of("x", 1))) {
                IllegalArgumentException error = assertThrows(name, IllegalArgumentException.class,
                        () -> evaluate(name, arguments(name, Arrays.asList(1, invalid), List.of(1))));
                assertTrue(error.getMessage(), error.getMessage().contains("index 1"));
            }
        }
        for (String name : List.of("compare_smaller", "compare_greater")) {
            for (Object threshold : Arrays.asList(null, "2", List.of(2), Double.NaN)) {
                assertThrows(IllegalArgumentException.class, () -> evaluate(name, List.of(), threshold));
            }
        }
        for (Object invalid : List.of(1, List.of("a"), List.of(Map.of("x", 1)), List.of(List.of(1)))) {
            assertThrows(IllegalArgumentException.class, () -> evaluate("concat_seq", List.of(1), invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> evaluate("mean", List.of()));
        assertThrows(IllegalArgumentException.class, () -> evaluate("calc_seq_sub", List.of("bad")));
        assertThrows(IllegalArgumentException.class,
                () -> evaluate("calc_seq_sub", List.of(Long.MIN_VALUE, Long.MAX_VALUE)));
        assertThrows(IllegalArgumentException.class,
                () -> evaluate("calc_seq_sub", List.of(-Double.MAX_VALUE, Double.MAX_VALUE)));
    }

    @Test
    public void supportsLogicalSequenceViewsAndBatchOrderInEveryDomain() {
        OperatorSequence view = view(List.of(99, 19, 3, 7, 88), 1, 3, 2);
        List<Integer> logical = List.of(19, 7, 3);
        for (String name : NAMES) {
            assertEquals(evaluate(name, arguments(name, logical, List.of(2))),
                    evaluate(name, arguments(name, view, view(List.of(100, 2), 1))));
            for (BatchDomain domain : BatchDomain.values()) {
                List<Object[]> rows = List.of(arguments(name, view, List.of(2)),
                        arguments(name, List.of(5, 9), List.of(1, 1)),
                        arguments(name, List.of(3), List.of()));
                List<BatchColumn> columns = new ArrayList<>();
                for (int arg = 0; arg < rows.get(0).length; arg++) {
                    List<Object> values = new ArrayList<>();
                    for (Object[] row : rows) values.add(row[arg]);
                    columns.add(new ListBatchColumn(values));
                }
                BatchOperatorResult batch = registry.evaluateBatch(name,
                        new BatchOperatorCall(new Layout(domain, rows.size()), columns));
                assertEquals(rows.size(), batch.values().size());
                for (int row = 0; row < rows.size(); row++) {
                    assertEquals(evaluate(name, rows.get(row)), batch.values().valueAt(row));
                }
            }
        }
    }

    @Test
    public void batchRecoveryKeepsHealthyRowsForEveryOperator() {
        for (String name : NAMES) {
            Object[] valid = arguments(name, TIMES, TIMES);
            List<BatchColumn> columns = new ArrayList<>();
            columns.add(new ListBatchColumn(List.of(valid[0], "invalid", valid[0])));
            if (valid.length == 2) columns.add(new ListBatchColumn(Collections.nCopies(3, valid[1])));
            BatchOperatorResult batch = registry.evaluateBatchRecovering(name,
                    new BatchOperatorCall(new Layout(BatchDomain.OFFLINE_ROW, 3), columns));
            assertEquals(Set.of(1), batch.rowFailures().keySet());
            assertEquals(evaluate(name, valid), batch.values().valueAt(0));
            assertEquals(evaluate(name, valid), batch.values().valueAt(2));
        }
    }

    @Test
    public void executesNestedAndNamedExpressionsAndEmptyMeanFallbackThroughPublicApi() {
        String config = """
                {"feature_set_name":"sequence-statistics","version":"1","features":[
                  {"name":"times","type":"BIGINT","definition_type":"BASE",
                   "entity_scopes":["USER"],"value_shape":"SEQUENCE"},
                  {"name":"before","type":"INT","definition_type":"DERIVED","value_shape":"SEQUENCE",
                   "expression":"compare_smaller(times, 8)"},
                  {"name":"after","type":"INT","definition_type":"DERIVED","value_shape":"SEQUENCE",
                   "expression":"compare_greater(sequence=times, value=8)"},
                  {"name":"diffs","type":"BIGINT","definition_type":"DERIVED","value_shape":"SEQUENCE",
                   "expression":"calc_seq_sub(times)","output_policy":"INTERNAL_ONLY"},
                  {"name":"average","type":"DOUBLE","definition_type":"DERIVED",
                   "expression":"mean(diffs)","dft":-1},
                  {"name":"nested","type":"DOUBLE","definition_type":"DERIVED",
                   "expression":"mean(calc_seq_sub([1,3,5,7,9,12,19]))"},
                  {"name":"decimal_diff","type":"DOUBLE","definition_type":"DERIVED","value_shape":"SEQUENCE",
                   "expression":"calc_seq_sub([0.1,0.3])"},
                  {"name":"joined","type":"STRING","definition_type":"DERIVED","value_shape":"SEQUENCE",
                   "expression":"concat_seq(['a','b','c'], ['d','e','f','a'])"},
                  {"name":"selected","type":"BIGINT","definition_type":"DERIVED","value_shape":"SEQUENCE",
                   "expression":"slice_by_indices(times, concat_seq(compare_smaller(times,8),compare_greater(times,8)))"},
                  {"name":"view_diff","type":"BIGINT","definition_type":"DERIVED","value_shape":"SEQUENCE",
                   "expression":"calc_seq_sub(slice_by_indices(times,[6,3,1]))","dft":-1}
                ]}
                """;
        FeatureDagEngine engine = FeatureDagEngine.init(config,
                InitOptions.builder().environment(ExecutionEnvironment.OFFLINE).build());
        Map<String, List<?>> result = engine.generate(new OfflineGenerateRequest("example", Map.of("times", TIMES))).featureValues();
        assertEquals(List.of(0, 1, 2, 3), result.get("before"));
        assertEquals(List.of(4, 5, 6), result.get("after"));
        assertEquals(List.of(3.0), result.get("average"));
        assertEquals(List.of(3.0), result.get("nested"));
        assertEquals(List.of(0.2), result.get("decimal_diff"));
        assertEquals(List.of("a", "b", "c", "d", "e", "f", "a"), result.get("joined"));
        assertEquals(List.of(1L, 3L, 5L, 7L, 9L, 12L, 19L), result.get("selected"));
        assertEquals(List.of(-12L, -4L), result.get("view_diff"));
        assertFalse(result.containsKey("diffs"));
        assertEquals(List.of(-1.0), engine.generate(new OfflineGenerateRequest("empty",
                Map.of("times", List.of()))).featureValues().get("average"));
    }

    private Object evaluate(String name, Object... arguments) {
        return registry.evaluate(name, Arrays.asList(arguments));
    }

    private static Object[] arguments(String name, Object first, Object second) {
        if (name.startsWith("compare_")) return new Object[]{first, 8};
        if (name.equals("concat_seq")) return new Object[]{first, second};
        return new Object[]{first};
    }

    private static OperatorSequence view(List<?> source, int... indices) {
        return new OperatorSequence() {
            public int size() { return indices.length; }
            public Object elementAt(int index) { return source.get(indices[index]); }
            public OperatorSequence filterByColumn(String column, Object value) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static void assertInference(OperatorInference result, DataType type, ValueShape shape, Set<EntityScope> scopes) {
        assertEquals(type, result.outputType());
        assertEquals(shape, result.valueShape());
        assertEquals(scopes, result.entityScopes());
    }

    private record Input(DataType outputType, ValueShape valueShape, Set<EntityScope> entityScopes)
            implements OperatorInputMetadata {
        Input(DataType type, ValueShape shape) { this(type, shape, Set.of(EntityScope.USER)); }
        public String sourceFeatureName() { return null; }
    }

    private record Layout(BatchDomain domain, int rowCount) implements BatchLayout {
        public int groupIndexAt(int row) { return -1; }
        public int indexInGroupAt(int row) { return row; }
    }
}
