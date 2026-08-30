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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 数值极值与四则运算算子的序列逐元素、标量广播及等长约束测试。 */
public final class NumericSequenceOperatorsTest {
    @Test
    public void inferenceReturnsSequenceWhenAnyNumericOperandIsSequence() {
        OperatorRegistry registry = OperatorRegistry.standard();
        TestInput intSequence = new TestInput(
                DataType.INT, Set.of(EntityScope.USER), ValueShape.SEQUENCE);
        TestInput doubleScalar = new TestInput(
                DataType.DOUBLE, Set.of(EntityScope.ITEM), ValueShape.SCALAR);

        for (String name : new String[] {"min", "max", "add", "sub", "mul", "div"}) {
            OperatorInference sequenceLeft = registry.infer(
                    name, List.of(intSequence, doubleScalar));
            assertEquals(name, DataType.DOUBLE, sequenceLeft.outputType());
            assertEquals(name, ValueShape.SEQUENCE, sequenceLeft.valueShape());
            assertEquals(
                    name,
                    Set.of(EntityScope.USER, EntityScope.ITEM),
                    sequenceLeft.entityScopes());

            OperatorInference sequenceRight = registry.infer(
                    name, List.of(doubleScalar, intSequence));
            assertEquals(name, ValueShape.SEQUENCE, sequenceRight.valueShape());
            assertTrue(name + " sequence view", registry.require(name).supportsSequenceView());
        }

        assertEquals(
                ValueShape.SCALAR,
                registry.infer("add", List.of(doubleScalar, doubleScalar)).valueShape());
    }

    @Test
    public void inferenceRejectsNonNumericSequenceElements() {
        OperatorRegistry registry = OperatorRegistry.standard();
        TestInput stringSequence = new TestInput(
                DataType.STRING, Set.of(EntityScope.USER), ValueShape.SEQUENCE);
        TestInput intScalar = new TestInput(
                DataType.INT, Set.of(EntityScope.USER), ValueShape.SCALAR);

        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> registry.infer("add", List.of(stringSequence, intScalar)));
        assertTrue(rejected.getMessage(), rejected.getMessage().contains("numeric input"));
    }

    @Test
    public void inferenceRejectsUnsupportedNumericValueShape() {
        OperatorRegistry registry = OperatorRegistry.standard();
        TestInput numericObject = new TestInput(
                DataType.INT, Set.of(EntityScope.USER), ValueShape.OBJECT);
        TestInput intScalar = new TestInput(
                DataType.INT, Set.of(EntityScope.USER), ValueShape.SCALAR);

        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> registry.infer("max", List.of(numericObject, intScalar)));
        assertTrue(rejected.getMessage(), rejected.getMessage().contains("value shape"));
    }

    @Test
    public void singleKernelBroadcastsScalarsAndZipsEqualLengthSequences() {
        OperatorRegistry registry = OperatorRegistry.standard();

        assertEquals(
                List.of(11L, 12L),
                registry.evaluate("add", List.of(List.of(1, 2), 10)));
        assertEquals(
                List.of(9L, 7L),
                registry.evaluate("sub", List.of(10, List.of(1, 3))));
        assertEquals(
                List.of(8L, 15L),
                registry.evaluate(
                        "mul",
                        List.of(
                                new TestOperatorSequence(List.of(2, 3)),
                                List.of(4, 5))));
        assertEquals(
                List.of(2.5, 0.0),
                registry.evaluate("div", List.of(List.of(10, 8), List.of(4, 0))));
        assertEquals(
                List.of(2, 1),
                registry.evaluate("min", List.of(List.of(3, 1), 2)));
        assertEquals(
                List.of(3, 4),
                registry.evaluate("max", List.of(List.of(3, 1), List.of(2, 4))));
        assertEquals(
                List.of(0, 1),
                registry.evaluate("min", List.of(List.of(3, 1), 2, List.of(0, 5))));
        assertEquals(
                List.of(),
                registry.evaluate("add", List.of(List.of(), 2)));
    }

    @Test
    public void sequenceEvaluationRejectsLengthMismatchAndReportsElementIndex() {
        OperatorRegistry registry = OperatorRegistry.standard();

        IllegalArgumentException mismatch = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate(
                        "add", List.of(List.of(1, 2), List.of(3))));
        assertTrue(mismatch.getMessage(), mismatch.getMessage().contains("equal length"));

        IllegalArgumentException variadicMismatch = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate(
                        "max", List.of(List.of(1, 2), 3, List.of(4))));
        assertTrue(
                variadicMismatch.getMessage(),
                variadicMismatch.getMessage().contains("equal length"));

        IllegalArgumentException badElement = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate(
                        "div", List.of(Arrays.<Object>asList(4, "x"), 2)));
        assertTrue(badElement.getMessage(), badElement.getMessage().contains("sequence index 1"));
    }

    @Test
    public void sequenceElementAccessFailureReportsLogicalIndex() {
        OperatorRegistry registry = OperatorRegistry.standard();

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate(
                        "add", List.of(new ThrowingOperatorSequence(), 1)));
        assertTrue(failure.getMessage(), failure.getMessage().contains("sequence index 1"));
        assertTrue(failure.getMessage(), failure.getMessage().contains("cannot read element"));
    }

    @Test
    public void scalarBatchAdapterEvaluatesOneSequencePerBatchRow() {
        OperatorRegistry registry = OperatorRegistry.standard();
        BatchOperatorCall call = new BatchOperatorCall(
                new FixedBatchLayout(BatchDomain.OFFLINE_ROW, 2),
                List.of(
                        new ListBatchColumn(List.of(List.of(1, 2), List.of(3, 4))),
                        new ListBatchColumn(List.of(10, 20))));

        BatchOperatorResult result = registry.evaluateBatch(
                "add", call, BatchKernelKind.SCALAR_ADAPTER);
        assertEquals(List.of(11L, 12L), result.values().valueAt(0));
        assertEquals(List.of(23L, 24L), result.values().valueAt(1));

        BatchOperatorCall mismatch = new BatchOperatorCall(
                new FixedBatchLayout(BatchDomain.OFFLINE_ROW, 2),
                List.of(
                        new ListBatchColumn(List.of(List.of(1), List.of(2, 3))),
                        new ListBatchColumn(List.of(List.of(4), List.of(5)))));
        BatchOperatorEvaluationException failure = assertThrows(
                BatchOperatorEvaluationException.class,
                () -> registry.evaluateBatch(
                        "mul", mismatch, BatchKernelKind.SCALAR_ADAPTER));
        assertEquals(1, failure.rowIndex());
        assertTrue(failure.getCause().getMessage().contains("equal length"));
    }

    @Test
    public void allSixOperatorsExecuteInOneSequenceDag() {
        FeatureDefinition numbers = FeatureDefinition.builder()
                .name("numbers")
                .role(FeatureRole.RAW)
                .dataType(DataType.DOUBLE)
                .addEntityScope(EntityScope.USER)
                .sourceBinding("numbers")
                .declaredValueShape(ValueShape.SEQUENCE)
                .build();
        FeatureDefinition offset = FeatureDefinition.raw(
                "offset", DataType.DOUBLE, EntityScope.USER, 1.0);
        FeatureDefinition resultFeature = FeatureDefinition.builder()
                .name("result")
                .role(FeatureRole.DERIVED)
                .dataType(DataType.DOUBLE)
                .expressionContent(
                        "min(max(div(mul(sub(add(numbers, offset), 1), 2), 2), 1), 5)")
                .outputPolicy(OutputPolicy.OUTPUT)
                .declaredValueShape(ValueShape.SEQUENCE)
                .build();

        OperatorRegistry registry = OperatorRegistry.standard();
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry)
                .build(List.of(numbers, offset, resultFeature), Set.of("result"));
        assertEquals(ValueShape.SEQUENCE, dag.featureOutput("result").valueShape());
        PhysicalPlan plan = new PhysicalPlanner(registry).plan(
                new LogicalDagOptimizer(registry).analyze(dag),
                ExecutionEnvironment.OFFLINE,
                "numeric-sequence-operators");
        ExecutionResult result = new DagRuntime(registry).execute(
                plan,
                ExecutionContext.offlineRow(
                        "numeric-sequence-operators",
                        Map.of("numbers", List.of(0.0, 2.0, 6.0), "offset", 2.0)));

        assertEquals(List.of(1.0, 3.0, 5.0), result.feature("result").raw());
    }

    private record TestInput(
            DataType outputType,
            Set<EntityScope> entityScopes,
            ValueShape valueShape) implements OperatorInputMetadata {
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

    private static final class ThrowingOperatorSequence implements OperatorSequence {
        @Override
        public int size() {
            return 2;
        }

        @Override
        public Object elementAt(int index) {
            if (index == 1) throw new IllegalStateException("cannot read element");
            return Integer.valueOf(2);
        }

        @Override
        public OperatorSequence filterByColumn(String column, Object value) {
            return this;
        }
    }
}
