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

public final class CosSimSeqOperatorTest {
    private final OperatorRegistry registry = OperatorRegistry.standard();

    @Test
    public void registersPureBinaryOperatorWithScalarBatchAdapter() {
        assertTrue(registry.find("cos_sim_seq").isPresent());
        OperatorDefinition definition = registry.require("cos_sim_seq");
        assertTrue(definition.deterministic());
        assertTrue(definition.sideEffectFree());
        assertEquals(BatchKernelKind.SCALAR_ADAPTER, registry.batchKernelKind("cos_sim_seq"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.evaluate("cos_sim_seq", List.of(List.of())));
        assertThrows(IllegalArgumentException.class,
                () -> registry.evaluate("cos_sim_seq", List.of(List.of(), List.of(), List.of())));
    }

    @Test
    public void computesCosinesInOriginalOrderWithoutMutatingInputs() {
        List<?> embeddings = List.of(List.of(1, 0), List.of(0, 1), List.of(-2L, 0), List.of(3, 4.0));
        assertValues(evaluate(embeddings, List.of(1.0, 0.0)), 1.0, 0.0, -1.0, 0.6);
        assertValues(evaluate(List.of(List.of(3, 4)), List.of(6.0, 8.0)), 1.0);
        assertEquals(List.of(List.of(1, 0), List.of(0, 1), List.of(-2L, 0), List.of(3, 4.0)), embeddings);
    }

    @Test
    public void handlesEmptySequencesAndZeroVectors() {
        assertEquals(List.of(), evaluate(List.of(), List.of(1.0)));
        assertValues(evaluate(List.of(List.of(0, 0), List.of(1, 0)), List.of(0.0, 0.0)), 0.0, 0.0);
        assertValues(evaluate(List.of(List.of(0, 0)), List.of(1.0, 0.0)), 0.0);
        assertValues(evaluate(List.of(List.of(), List.of()), List.of()), 0.0, 0.0);
    }

    @Test
    public void handlesFiniteValuesWhoseSquaresOverflowOrUnderflow() {
        for (double scale : new double[] {Double.MAX_VALUE, Double.MIN_VALUE, 1e-200, 1e200}) {
            assertValues(evaluate(List.of(List.of(scale, scale), List.of(-scale, -scale),
                    List.of(scale, -scale)), List.of(scale, scale)), 1.0, -1.0, 0.0);
        }
        assertValues(evaluate(List.of(List.of(1e-200, 1e200)), List.of(0.0, 1.0)), 1.0);
    }

    @Test
    public void rejectsMalformedVectorsEvenWhenTargetIsZero() {
        for (Object invalid : Arrays.asList(null, "1", true, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException.class,
                    () -> evaluate(List.of(Arrays.asList(invalid)), List.of(0.0)));
            assertThrows(IllegalArgumentException.class,
                    () -> evaluate(List.of(), Arrays.asList(invalid)));
        }
        for (Object invalid : Arrays.asList(null, "vector", 5, List.of(1, 2))) {
            assertThrows(IllegalArgumentException.class,
                    () -> evaluate(Arrays.asList(invalid), List.of(0.0)));
        }
        assertThrows(IllegalArgumentException.class, () -> evaluate(null, List.of(1.0)));
        assertThrows(IllegalArgumentException.class, () -> evaluate(List.of(), null));
    }

    @Test
    public void infersDoubleSequenceAndUnionOfInputScopes() {
        OperatorInference result = registry.infer("cos_sim_seq", List.of(
                new Input(DataType.OBJECT, ValueShape.SEQUENCE, EntityScope.USER),
                new Input(DataType.DOUBLE, ValueShape.SEQUENCE, EntityScope.ITEM)));
        assertEquals(DataType.DOUBLE, result.outputType());
        assertEquals(ValueShape.SEQUENCE, result.valueShape());
        assertEquals(Set.of(EntityScope.USER, EntityScope.ITEM), result.entityScopes());
        for (DataType invalid : DataType.values()) {
            if (invalid != DataType.OBJECT) {
                assertThrows(IllegalArgumentException.class, () -> infer(invalid, ValueShape.SEQUENCE,
                        DataType.DOUBLE, ValueShape.SEQUENCE));
            }
            if (invalid != DataType.DOUBLE) {
                assertThrows(IllegalArgumentException.class, () -> infer(DataType.OBJECT, ValueShape.SEQUENCE,
                        invalid, ValueShape.SEQUENCE));
            }
        }
        for (ValueShape shape : ValueShape.values()) {
            if (shape == ValueShape.SEQUENCE) continue;
            assertThrows(IllegalArgumentException.class, () -> infer(DataType.OBJECT, shape,
                    DataType.DOUBLE, ValueShape.SEQUENCE));
            assertThrows(IllegalArgumentException.class, () -> infer(DataType.OBJECT, ValueShape.SEQUENCE,
                    DataType.DOUBLE, shape));
        }
    }

    @Test
    public void supportsSequenceViewsAndBatchRowOrder() {
        Object embeddings = view(List.of(List.of(0, 1), List.of(1, 0)));
        Object target = view(List.of(1.0, 0.0));
        assertValues(evaluate(embeddings, target), 0.0, 1.0);
        BatchLayout layout = new BatchLayout() {
            public BatchDomain domain() { return BatchDomain.OFFLINE_ROW; }
            public int rowCount() { return 3; }
            public int groupIndexAt(int row) { return -1; }
            public int indexInGroupAt(int row) { return row; }
        };
        BatchOperatorResult result = registry.evaluateBatch("cos_sim_seq", new BatchOperatorCall(layout,
                List.of(new ListBatchColumn(List.of(embeddings, List.of(), List.of(List.of(-1, 0)))),
                        new ListBatchColumn(List.of(target, List.of(0.0, 1.0), target)))));
        assertEquals(3, result.values().size());
        assertEquals(List.of(List.of(0.0, 1.0), List.of(), List.of(-1.0)),
                ((ListBatchColumn) result.values()).values());
    }

    private Object evaluate(Object embeddings, Object target) {
        return registry.evaluate("cos_sim_seq", Arrays.asList(embeddings, target));
    }

    @Test
    public void executesExpressionThroughPublicApiAndNestedSelection() {
        String config = """
                {"feature_set_name":"cos-sim-seq","version":"1","features":[
                  {"name":"emb_seq","type":"OBJECT","definition_type":"BASE",
                   "entity_scopes":["USER"],"value_shape":"SEQUENCE"},
                  {"name":"target_emb","type":"DOUBLE","definition_type":"BASE",
                   "entity_scopes":["USER"],"value_shape":"SEQUENCE"},
                  {"name":"similarities","type":"DOUBLE","value_shape":"SEQUENCE",
                   "definition_type":"DERIVED","expression":"cos_sim_seq(emb_seq, target_emb)"},
                  {"name":"selected","type":"DOUBLE","value_shape":"SEQUENCE",
                   "definition_type":"DERIVED",
                   "expression":"cos_sim_seq(slice_by_indices(emb_seq, [1, 0]), target_emb)"}
                ]}
                """;
        FeatureDagEngine engine = FeatureDagEngine.init(config,
                InitOptions.builder().environment(ExecutionEnvironment.OFFLINE).build());
        Map<String, List<?>> values = engine.generate(new OfflineGenerateRequest("row",
                Map.of("emb_seq", List.of(List.of(1, 0), List.of(0, 1)),
                        "target_emb", List.of(1.0, 0.0)))).featureValues();
        assertEquals(List.of(1.0, 0.0), values.get("similarities"));
        assertEquals(List.of(0.0, 1.0), values.get("selected"));
    }

    private void infer(DataType left, ValueShape leftShape, DataType right, ValueShape rightShape) {
        registry.infer("cos_sim_seq", List.of(new Input(left, leftShape, EntityScope.USER),
                new Input(right, rightShape, EntityScope.USER)));
    }

    private static void assertValues(Object actual, double... expected) {
        List<?> values = (List<?>) actual;
        assertEquals(expected.length, values.size());
        for (int index = 0; index < expected.length; index++) {
            assertTrue(values.get(index) instanceof Double);
            assertEquals(expected[index], (Double) values.get(index), 1e-14);
        }
    }

    private static OperatorSequence view(List<?> values) {
        return new OperatorSequence() {
            public int size() { return values.size(); }
            public Object elementAt(int index) { return values.get(index); }
            public OperatorSequence filterByColumn(String column, Object value) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private record Input(DataType outputType, ValueShape valueShape, EntityScope scope)
            implements OperatorInputMetadata {
        public Set<EntityScope> entityScopes() { return Collections.singleton(scope); }
        public String sourceFeatureName() { return "test"; }
    }
}
