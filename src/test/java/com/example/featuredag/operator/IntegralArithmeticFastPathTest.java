package com.example.featuredag.operator;

import org.junit.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.*;

/** 快速整型路径必须与原有精确十进制语义一致，包括错误文本和逐行恢复。 */
public class IntegralArithmeticFastPathTest {
    private final OperatorRegistry registry = OperatorRegistry.standard();

    @Test
    public void primitiveIntegerCarriersAndLongBoundariesMatchDecimalOracle() {
        Number[] values = {Byte.MIN_VALUE, Byte.MAX_VALUE, Short.MIN_VALUE, Short.MAX_VALUE,
                Integer.MIN_VALUE, Integer.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE,
                -1L, 0L, 1L, 3037000499L, 3037000500L};
        for (String operator : List.of("add", "mul")) {
            for (Number left : values) {
                for (Number right : values) assertIntegralOracle(operator, left, right);
            }
            Random random = new Random(24092026);
            for (int i = 0; i < 1000; i++) {
                assertIntegralOracle(operator, random.nextLong(), random.nextLong());
                assertIntegralOracle(operator, random.nextInt(), random.nextInt());
            }
        }
    }

    @Test
    public void wideIntegersAndDecimalInputsRetainTheirExistingSemantics() {
        BigInteger outsideLong = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
        assertEquals(Long.MAX_VALUE, registry.evaluate("add", List.of(outsideLong, -1)));
        assertEquals(0L, registry.evaluate("mul", List.of(outsideLong, 0)));
        assertEquals(0.3d, registry.evaluate("add", List.of(0.1d, 0.2d)));
        assertEquals(0.02d, registry.evaluate("mul", List.of(0.1d, 0.2d)));
        assertEquals(0.3d, registry.evaluate("add", List.of(0.1f, 0.2f)));
        assertEquals(3.0d, registry.evaluate("add", List.of(new BigDecimal("1"), 2)));
        assertEquals(6.0d, registry.evaluate("mul", List.of(2L, 3.0d)));
        for (String operator : List.of("add", "mul")) {
            for (Object invalid : Arrays.asList(Double.NaN, Double.POSITIVE_INFINITY,
                    Float.NEGATIVE_INFINITY)) {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> registry.evaluate(operator, List.of(1L, invalid)));
                assertEquals(operator + " requires finite numeric values", error.getMessage());
            }
            assertEquals("Expected numeric value, got: text", assertThrows(
                    IllegalArgumentException.class,
                    () -> registry.evaluate(operator, List.of(1, "text"))).getMessage());
        }
    }

    @Test
    public void sequenceBroadcastPreservesValuesImmutabilityAndFailureIndex() {
        assertEquals(List.of(3L, 4L), registry.evaluate("add", List.of(List.of(1, 2), 2)));
        List<?> output = (List<?>) registry.evaluate("mul", List.of(2, List.of(3L, -4L)));
        assertEquals(List.of(6L, -8L), output);
        assertThrows(UnsupportedOperationException.class, output::clear);
        for (String operator : List.of("add", "mul")) {
            String exact = "add".equals(operator) ? "9223372036854775809" : "18446744073709551614";
            assertEquals(operator + " failed at sequence index 1: " + operator
                    + " overflow for result: " + exact, assertThrows(IllegalArgumentException.class,
                    () -> registry.evaluate(operator, List.of(List.of(1L, Long.MAX_VALUE), 2L)))
                    .getMessage());
        }
    }

    @Test
    public void batchKeepsOverflowIsolatedAndMatchesSingleInEveryRow() {
        List<Object> left = List.of(7L, Long.MAX_VALUE, Long.MIN_VALUE, (short) 6);
        List<Object> right = List.of(2L, 2L, -1L, (byte) 3);
        BatchLayout layout = new BatchLayout() {
            public BatchDomain domain() { return BatchDomain.ONLINE_CANDIDATE; }
            public int rowCount() { return left.size(); }
            public int groupIndexAt(int row) { return 0; }
            public int indexInGroupAt(int row) { return row; }
        };
        BatchOperatorCall call = new BatchOperatorCall(layout,
                List.of(new ListBatchColumn(left), new ListBatchColumn(right)));
        for (String operator : List.of("add", "mul")) {
            assertEquals(BatchKernelKind.SCALAR_ADAPTER, registry.batchKernelKind(operator));
            BatchOperatorResult result = registry.evaluateBatchRecovering(operator, call);
            assertEquals(Set.of(1, 2), result.rowFailures().keySet());
            for (int row = 0; row < left.size(); row++) {
                final int index = row;
                if (result.rowFailures().containsKey(row)) {
                    RuntimeException single = assertThrows(IllegalArgumentException.class,
                            () -> registry.evaluate(operator, List.of(left.get(index), right.get(index))));
                    assertEquals(single.getMessage(), result.rowFailures().get(row).getMessage());
                } else {
                    assertEquals(registry.evaluate(operator, List.of(left.get(row), right.get(row))),
                            result.values().valueAt(row));
                }
            }
        }
    }

    private void assertIntegralOracle(String operator, Number left, Number right) {
        BigDecimal a = BigDecimal.valueOf(left.longValue());
        BigDecimal b = BigDecimal.valueOf(right.longValue());
        BigDecimal expected = "add".equals(operator) ? a.add(b) : a.multiply(b);
        long value;
        try {
            value = expected.longValueExact();
        } catch (ArithmeticException overflow) {
            assertEquals(operator + " overflow for result: " + expected,
                    assertThrows(IllegalArgumentException.class,
                            () -> registry.evaluate(operator, List.of(left, right))).getMessage());
            return;
        }
        Object result = registry.evaluate(operator, List.of(left, right));
        assertEquals(Long.class, result.getClass());
        assertEquals(value, result);
    }
}
