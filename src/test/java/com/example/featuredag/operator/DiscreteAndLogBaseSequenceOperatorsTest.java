package com.example.featuredag.operator;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.FeatureDefinition;
import com.example.featuredag.definition.FeatureRole;
import com.example.featuredag.definition.OutputPolicy;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.physical.PhysicalPlan;
import com.example.featuredag.physical.PhysicalPlanner;
import com.example.featuredag.planning.LogicalDagOptimizer;
import com.example.featuredag.runtime.DagRuntime;
import com.example.featuredag.runtime.ExecutionContext;
import com.example.featuredag.runtime.ExecutionResult;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** discrete 与 log_base 的数值序列契约测试。 */
public final class DiscreteAndLogBaseSequenceOperatorsTest {
    @Test
    public void discreteBucketsEachValueAgainstSharedBoundaries() {
        OperatorRegistry registry = OperatorRegistry.standard();

        assertEquals(
                List.of(0, 1, 2),
                registry.evaluate(
                        "discrete",
                        List.of(List.of(5, 15, 25), List.of(10, 20))));
        assertEquals(
                List.of(),
                registry.evaluate("discrete", List.of(List.of(), List.of(10, 20))));
    }

    @Test
    public void discreteInferenceFollowsOnlyTheValueShape() {
        OperatorRegistry registry = OperatorRegistry.standard();
        TestInput sequenceValue = new TestInput(DataType.INT, ValueShape.SEQUENCE);
        TestInput scalarValue = new TestInput(DataType.INT, ValueShape.SCALAR);
        TestInput boundaries = new TestInput(DataType.INT, ValueShape.SEQUENCE);

        OperatorInference sequence = registry.infer(
                "discrete", List.of(sequenceValue, boundaries));
        assertEquals(DataType.INT, sequence.outputType());
        assertEquals(ValueShape.SEQUENCE, sequence.valueShape());
        assertEquals(
                ValueShape.SCALAR,
                registry.infer("discrete", List.of(scalarValue, boundaries)).valueShape());
        assertTrue(registry.require("discrete").supportsSequenceView());
    }

    @Test
    public void logBaseBroadcastsValueAndBaseSequences() {
        OperatorRegistry registry = OperatorRegistry.standard();

        assertEquals(
                List.of(0.0, 3.0, 6.0),
                registry.evaluate(
                        "log_base", List.of(List.of(1, 8, 128), 2, 64)));
        assertEquals(
                List.of(6.0, 3.0, 2.0),
                registry.evaluate(
                        "log_base", List.of(64, List.of(2, 4, 8), 64)));
        assertEquals(
                List.of(3.0, 2.0),
                registry.evaluate(
                        "log_base", List.of(List.of(8, 16), List.of(2, 4), 64)));
        assertEquals(
                List.of(),
                registry.evaluate("log_base", List.of(List.of(), 2, 64)));
    }

    @Test
    public void logBaseRejectsMismatchedValueAndBaseSequences() {
        OperatorRegistry registry = OperatorRegistry.standard();

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate(
                        "log_base", List.of(List.of(8, 16), List.of(2), 64)));

        assertTrue(failure.getMessage(), failure.getMessage().contains("equal length"));
    }

    @Test
    public void logBaseInferenceFollowsValueAndBaseButNotUpboundShape() {
        OperatorRegistry registry = OperatorRegistry.standard();
        TestInput scalar = new TestInput(DataType.INT, ValueShape.SCALAR);
        TestInput sequence = new TestInput(DataType.DOUBLE, ValueShape.SEQUENCE);

        assertEquals(
                ValueShape.SEQUENCE,
                registry.infer("log_base", List.of(sequence, scalar, scalar)).valueShape());
        assertEquals(
                ValueShape.SEQUENCE,
                registry.infer("log_base", List.of(scalar, sequence, scalar)).valueShape());
        assertEquals(
                ValueShape.SCALAR,
                registry.infer("log_base", List.of(scalar, scalar, scalar)).valueShape());
        assertTrue(registry.require("log_base").supportsSequenceView());

        OperatorInference scoped = registry.infer(
                "log_base",
                List.of(
                        new TestInput(
                                DataType.DOUBLE,
                                ValueShape.SEQUENCE,
                                Set.of(EntityScope.USER)),
                        new TestInput(
                                DataType.INT,
                                ValueShape.SCALAR,
                                Set.of(EntityScope.ITEM)),
                        new TestInput(
                                DataType.INT,
                                ValueShape.SCALAR,
                                Set.of(EntityScope.SCENE))));
        assertEquals(
                Set.of(EntityScope.USER, EntityScope.ITEM, EntityScope.SCENE),
                scoped.entityScopes());

        IllegalArgumentException rejectedUpbound = assertThrows(
                IllegalArgumentException.class,
                () -> registry.infer("log_base", List.of(scalar, scalar, sequence)));
        assertTrue(
                rejectedUpbound.getMessage(),
                rejectedUpbound.getMessage().contains("upbound")
                        && rejectedUpbound.getMessage().contains("scalar"));
    }

    @Test
    public void discreteAndLogBaseConsumeSequenceViews() {
        OperatorRegistry registry = OperatorRegistry.standard();

        assertEquals(
                List.of(0, 1),
                registry.evaluate(
                        "discrete",
                        List.of(
                                new TestOperatorSequence(List.of(5, 15)),
                                new TestOperatorSequence(List.of(10)))));
        assertEquals(
                List.of(3.0, 2.0),
                registry.evaluate(
                        "log_base",
                        List.of(
                                new TestOperatorSequence(List.of(8, 16)),
                                new TestOperatorSequence(List.of(2, 4)),
                                64)));
    }

    @Test
    public void discreteInferenceRejectsNonNumericValuesAndScalarBoundaries() {
        OperatorRegistry registry = OperatorRegistry.standard();
        TestInput stringSequence = new TestInput(DataType.STRING, ValueShape.SEQUENCE);
        TestInput numericSequence = new TestInput(DataType.INT, ValueShape.SEQUENCE);
        TestInput numericScalar = new TestInput(DataType.INT, ValueShape.SCALAR);

        IllegalArgumentException nonNumericValue = assertThrows(
                IllegalArgumentException.class,
                () -> registry.infer(
                        "discrete", List.of(stringSequence, numericSequence)));
        assertTrue(
                nonNumericValue.getMessage(),
                nonNumericValue.getMessage().contains("numeric value"));

        IllegalArgumentException scalarBoundaries = assertThrows(
                IllegalArgumentException.class,
                () -> registry.infer(
                        "discrete", List.of(numericScalar, numericScalar)));
        assertTrue(
                scalarBoundaries.getMessage(),
                scalarBoundaries.getMessage().contains("boundaries")
                        && scalarBoundaries.getMessage().contains("sequence"));

        IllegalArgumentException nonNumericBoundaries = assertThrows(
                IllegalArgumentException.class,
                () -> registry.infer(
                        "discrete", List.of(numericScalar, stringSequence)));
        assertTrue(
                nonNumericBoundaries.getMessage(),
                nonNumericBoundaries.getMessage().contains("numeric boundaries"));
    }

    @Test
    public void logBaseValidatesSharedUpboundEvenForEmptyValueSequence() {
        OperatorRegistry registry = OperatorRegistry.standard();

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("log_base", List.of(List.of(), 2, 0)));

        assertTrue(failure.getMessage(), failure.getMessage().contains("upbound"));
    }

    @Test
    public void arrayLiteralSequencesBuildAndExecuteThroughDag() {
        FeatureDefinition bucketed = FeatureDefinition.builder()
                .name("bucketed")
                .role(FeatureRole.DERIVED)
                .dataType(DataType.INT)
                .expressionContent("discrete([5, 15, 25], [10, 20])")
                .outputPolicy(OutputPolicy.OUTPUT)
                .declaredValueShape(ValueShape.SEQUENCE)
                .build();
        FeatureDefinition logged = FeatureDefinition.builder()
                .name("logged")
                .role(FeatureRole.DERIVED)
                .dataType(DataType.DOUBLE)
                .expressionContent("log_base([1, 8, 128], 2, 64)")
                .outputPolicy(OutputPolicy.OUTPUT)
                .declaredValueShape(ValueShape.SEQUENCE)
                .build();
        OperatorRegistry registry = OperatorRegistry.standard();
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry)
                .build(List.of(bucketed, logged), Set.of("bucketed", "logged"));
        PhysicalPlan plan = new PhysicalPlanner(registry).plan(
                new LogicalDagOptimizer(registry).analyze(dag),
                ExecutionEnvironment.OFFLINE,
                "discrete-log-base-sequences");
        ExecutionResult result = new DagRuntime(registry).execute(
                plan,
                ExecutionContext.offlineRow(
                        "discrete-log-base-sequences", Map.of()));

        assertEquals(List.of(0, 1, 2), result.feature("bucketed").raw());
        assertEquals(List.of(0.0, 3.0, 6.0), result.feature("logged").raw());
    }

    @Test
    public void scalarBatchAdapterKeepsSequenceDimensionsInsideEachRow() {
        OperatorRegistry registry = OperatorRegistry.standard();
        assertEquals(BatchKernelKind.SCALAR_ADAPTER, registry.batchKernelKind("discrete"));
        assertEquals(BatchKernelKind.SCALAR_ADAPTER, registry.batchKernelKind("log_base"));

        BatchOperatorCall discreteCall = new BatchOperatorCall(
                new FixedBatchLayout(BatchDomain.OFFLINE_ROW, 2),
                List.of(
                        new ListBatchColumn(List.of(List.of(5, 15), List.of(25))),
                        new ListBatchColumn(
                                List.of(List.of(10), List.of(10, 20)))));
        BatchOperatorResult discreteResult = registry.evaluateBatch(
                "discrete", discreteCall, BatchKernelKind.SCALAR_ADAPTER);
        assertEquals(List.of(0, 1), discreteResult.values().valueAt(0));
        assertEquals(List.of(2), discreteResult.values().valueAt(1));

        BatchOperatorCall logCall = new BatchOperatorCall(
                new FixedBatchLayout(BatchDomain.OFFLINE_ROW, 2),
                List.of(
                        new ListBatchColumn(List.of(List.of(1, 8), 64)),
                        new ListBatchColumn(List.of(2, List.of(2, 4))),
                        new ListBatchColumn(List.of(64, 64))));
        BatchOperatorResult logResult = registry.evaluateBatch(
                "log_base", logCall, BatchKernelKind.SCALAR_ADAPTER);
        assertEquals(List.of(0.0, 3.0), logResult.values().valueAt(0));
        assertEquals(List.of(6.0, 3.0), logResult.values().valueAt(1));
    }

    private record TestInput(
            DataType outputType,
            ValueShape valueShape,
            Set<EntityScope> entityScopes) implements OperatorInputMetadata {
        private TestInput(DataType outputType, ValueShape valueShape) {
            this(outputType, valueShape, Set.of(EntityScope.USER));
        }

        @Override
        public String sourceFeatureName() {
            return "test-source";
        }
    }

    private record FixedBatchLayout(
            BatchDomain domain,
            int rowCount) implements BatchLayout {
        @Override
        public int groupIndexAt(int rowIndex) {
            return -1;
        }

        @Override
        public int indexInGroupAt(int rowIndex) {
            return rowIndex;
        }
    }

    private static final class TestOperatorSequence implements OperatorSequence {
        private final List<?> values;

        private TestOperatorSequence(List<?> values) {
            this.values = List.copyOf(values);
        }

        @Override
        public int size() {
            return values.size();
        }

        @Override
        public Object elementAt(int index) {
            return values.get(index);
        }

        @Override
        public OperatorSequence filterByColumn(String column, Object value) {
            return this;
        }
    }
}
