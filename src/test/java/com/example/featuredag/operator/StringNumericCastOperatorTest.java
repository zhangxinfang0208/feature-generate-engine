package com.example.featuredag.operator;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.ValueShape;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 数字字符串通过 to_int / to_bigint 显式转换的推断、求值、异常与 Batch 测试。 */
public final class StringNumericCastOperatorTest {
    @Test
    public void inferenceAcceptsStringScalarAndSequence() {
        OperatorRegistry registry = OperatorRegistry.standard();

        OperatorInference scalar = registry.infer(
                "to_bigint",
                List.of(new TestInput(DataType.STRING, ValueShape.SCALAR)));
        assertEquals(DataType.BIGINT, scalar.outputType());
        assertEquals(ValueShape.SCALAR, scalar.valueShape());

        OperatorInference sequence = registry.infer(
                "to_int",
                List.of(new TestInput(DataType.STRING, ValueShape.SEQUENCE)));
        assertEquals(DataType.INT, sequence.outputType());
        assertEquals(ValueShape.SEQUENCE, sequence.valueShape());
    }

    @Test
    public void evaluationParsesDecimalStringsWithoutLosingBigintPrecision() {
        OperatorRegistry registry = OperatorRegistry.standard();

        assertEquals(Integer.valueOf(3), registry.evaluate("to_int", List.of("3.7")));
        assertEquals(Integer.valueOf(-3), registry.evaluate("to_int", List.of(" -3.7 ")));
        assertEquals(
                Long.valueOf(9007199254740993L),
                registry.evaluate("to_bigint", List.of("9007199254740993")));
        assertEquals(
                Arrays.asList(1, -2),
                registry.evaluate("to_int", List.of(Arrays.asList("1.9", "-2.8"))));
    }

    @Test
    public void rejectsBlankInvalidAndOverflowingStrings() {
        OperatorRegistry registry = OperatorRegistry.standard();

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("to_int", List.of("   ")));
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("to_bigint", List.of("not-a-number")));
        IllegalArgumentException overflow = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("to_bigint", List.of("9223372036854775808")));
        assertTrue(overflow.getMessage().contains("overflow"));
    }

    @Test
    public void batchUsesScalarAdapterAndReportsInvalidRow() {
        OperatorRegistry registry = OperatorRegistry.standard();
        BatchOperatorCall valid = new BatchOperatorCall(
                new FixedBatchLayout(2),
                List.of(new ListBatchColumn(List.of("3.7", "5"))));

        BatchOperatorResult result = registry.evaluateBatch(
                "to_int", valid, BatchKernelKind.SCALAR_ADAPTER);
        assertEquals(Integer.valueOf(3), result.values().valueAt(0));
        assertEquals(Integer.valueOf(5), result.values().valueAt(1));

        BatchOperatorCall invalid = new BatchOperatorCall(
                new FixedBatchLayout(2),
                List.of(new ListBatchColumn(List.of("3", "invalid"))));
        BatchOperatorEvaluationException failure = assertThrows(
                BatchOperatorEvaluationException.class,
                () -> registry.evaluateBatch(
                        "to_bigint", invalid, BatchKernelKind.SCALAR_ADAPTER));
        assertEquals(1, failure.rowIndex());
    }

    private record TestInput(
            DataType outputType,
            ValueShape valueShape) implements OperatorInputMetadata {
        @Override
        public Set<EntityScope> entityScopes() {
            return Set.of(EntityScope.USER);
        }

        @Override
        public String sourceFeatureName() {
            return "string-source";
        }
    }

    private record FixedBatchLayout(int rowCount) implements BatchLayout {
        @Override
        public BatchDomain domain() {
            return BatchDomain.OFFLINE_ROW;
        }

        @Override
        public int groupIndexAt(int rowIndex) {
            return -1;
        }

        @Override
        public int indexInGroupAt(int rowIndex) {
            return rowIndex;
        }
    }
}
