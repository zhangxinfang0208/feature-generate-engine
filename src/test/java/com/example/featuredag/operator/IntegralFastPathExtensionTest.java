package com.example.featuredag.operator;

import org.junit.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

/**
 * sub、calc_seq_sub、compare_*、min/max、to_int/to_bigint 新增的整型快路径
 * 必须与精确十进制语义一致：结果值、结果载体、相等时保留最左输入，以及溢出和非法输入的错误文本。
 */
public class IntegralFastPathExtensionTest {
    private final OperatorRegistry registry = OperatorRegistry.standard();

    private static final Number[] CARRIERS = {
            Byte.MIN_VALUE, Byte.MAX_VALUE, Short.MIN_VALUE, Short.MAX_VALUE,
            Integer.MIN_VALUE, Integer.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE,
            -1L, 0L, 1L, Integer.MIN_VALUE + 1L, Integer.MAX_VALUE - 1L};

    @Test
    public void subMatchesTheDecimalOracleForEveryIntegralCarrierPair() {
        for (Number left : CARRIERS) {
            for (Number right : CARRIERS) assertSubOracle(left, right);
        }
        Random random = new Random(24112026);
        for (int round = 0; round < 1000; round++) {
            assertSubOracle(random.nextLong(), random.nextLong());
            assertSubOracle(random.nextInt(), random.nextInt());
        }
    }

    @Test
    public void calcSeqSubMatchesTheDecimalOracleIncludingOverflow() {
        for (Number first : CARRIERS) {
            for (Number second : CARRIERS) {
                List<Number> sequence = Arrays.asList(first, second, 0L, first);
                List<Object> expected = new ArrayList<>();
                String failure = null;
                for (int index = 1; index < sequence.size() && failure == null; index++) {
                    BigDecimal difference = BigDecimal.valueOf(sequence.get(index).longValue())
                            .subtract(BigDecimal.valueOf(sequence.get(index - 1).longValue()));
                    try {
                        expected.add(Long.valueOf(difference.longValueExact()));
                    } catch (ArithmeticException overflow) {
                        failure = "calc_seq_sub failed at sequence index " + index
                                + ": calc_seq_sub overflow for result: " + difference;
                    }
                }
                if (failure == null) {
                    assertEquals(expected, registry.evaluate("calc_seq_sub", List.of(sequence)));
                } else {
                    assertEquals(failure, assertThrows(IllegalArgumentException.class,
                            () -> registry.evaluate("calc_seq_sub", List.of(sequence))).getMessage());
                }
            }
        }
        assertEquals(List.of(), registry.evaluate("calc_seq_sub", List.of(Arrays.asList(5L))));
        assertEquals(List.of(), registry.evaluate("calc_seq_sub", List.of(Arrays.asList())));
        // 单元素序列里的非有限数仍按原来的方式报错。
        assertEquals("calc_seq_sub failed at sequence index 0: calc_seq_sub requires finite numeric values",
                assertThrows(IllegalArgumentException.class,
                        () -> registry.evaluate("calc_seq_sub", List.of(Arrays.asList(Double.NaN)))).getMessage());
    }

    @Test
    public void comparisonsMatchDecimalComparisonForMixedCarriers() {
        Object[] elements = {1, 2L, 3.0, new BigInteger("4"), new BigDecimal("2.5"), (short) 7, (byte) 7};
        Object[] thresholds = {2, 2L, 2.5, new BigInteger("3"), 7L, Long.MIN_VALUE, (byte) 7};
        for (String operator : List.of("compare_smaller", "compare_greater")) {
            boolean smaller = "compare_smaller".equals(operator);
            for (Object threshold : thresholds) {
                List<Integer> expected = new ArrayList<>();
                for (int index = 0; index < elements.length; index++) {
                    int comparison = decimal(elements[index]).compareTo(decimal(threshold));
                    if (smaller ? comparison < 0 : comparison > 0) expected.add(index);
                }
                assertEquals(operator + " threshold " + threshold, expected,
                        registry.evaluate(operator, List.of(Arrays.asList(elements), threshold)));
            }
            assertEquals(operator + " failed at sequence index 1: " + operator + " requires finite numeric values",
                    assertThrows(IllegalArgumentException.class,
                            () -> registry.evaluate(operator, List.of(Arrays.asList(1, Double.NaN), 2)))
                            .getMessage());
            assertEquals(operator + " requires finite numeric values", assertThrows(
                    IllegalArgumentException.class,
                    () -> registry.evaluate(operator, List.of(Arrays.asList(1, 2), Double.NaN))).getMessage());
            assertEquals(operator + " failed at sequence index 1: Expected numeric value, got: x",
                    assertThrows(IllegalArgumentException.class,
                            () -> registry.evaluate(operator, List.of(Arrays.asList(1, "x"), 2))).getMessage());
        }
    }

    @Test
    public void minAndMaxKeepTheLeftmostWinnerAndItsCarrier() {
        Number five = Integer.valueOf(5);
        Number alsoFive = Long.valueOf(5);
        assertSame(five, registry.evaluate("min", List.of(five, alsoFive, 9L)));
        assertSame(five, registry.evaluate("max", List.of(five, alsoFive, 1L)));
        assertSame(alsoFive, registry.evaluate("min", List.of(9L, alsoFive, 5)));
        assertEquals(Long.MAX_VALUE, registry.evaluate("max", List.of(1L, Long.MAX_VALUE, Long.MIN_VALUE)));
        assertEquals(Long.MIN_VALUE, registry.evaluate("min", List.of(1L, Long.MAX_VALUE, Long.MIN_VALUE)));
        // 混入浮点或宽整数时回到精确十进制比较，返回胜出参数的原载体。
        assertSame(five, registry.evaluate("min", List.of(five, 5.0)));
        assertEquals(5.0d, registry.evaluate("min", List.of(9L, 5.0)));
        assertEquals(new BigInteger("4"), registry.evaluate("max", List.of(new BigInteger("4"), 3L)));

        Random random = new Random(24112027);
        for (int round = 0; round < 500; round++) {
            Number[] values = {random.nextInt(), random.nextLong(), random.nextInt(200) - 100};
            for (boolean minimum : new boolean[] {true, false}) {
                Number expected = values[0];
                for (Number value : values) {
                    if (minimum ? value.longValue() < expected.longValue()
                            : value.longValue() > expected.longValue()) {
                        expected = value;
                    }
                }
                assertSame(expected, registry.evaluate(minimum ? "min" : "max", List.of(values)));
            }
        }
    }

    @Test
    public void castsKeepExactValuesAndOverflowMessagesForIntegralCarriers() {
        assertEquals(5, registry.evaluate("to_int", List.of(5)));
        assertEquals(5, registry.evaluate("to_int", List.of((short) 5)));
        assertEquals(5, registry.evaluate("to_int", List.of((byte) 5)));
        assertEquals(Integer.MAX_VALUE, registry.evaluate("to_int", List.of(Integer.MAX_VALUE)));
        assertEquals(Integer.MIN_VALUE, registry.evaluate("to_int", List.of((long) Integer.MIN_VALUE)));
        assertEquals("to_int overflow for value: 2147483648", assertThrows(IllegalArgumentException.class,
                () -> registry.evaluate("to_int", List.of(2147483648L))).getMessage());
        assertEquals("to_int overflow for value: -2147483649", assertThrows(IllegalArgumentException.class,
                () -> registry.evaluate("to_int", List.of(-2147483649L))).getMessage());
        assertEquals(List.of(1, 2), registry.evaluate("to_int", List.of(Arrays.asList(1L, (short) 2))));
        for (Number carrier : CARRIERS) {
            assertEquals(carrier.longValue(), registry.evaluate("to_bigint", List.of(carrier)));
        }
        assertEquals(List.of(1L, 2L), registry.evaluate("to_bigint", List.of(Arrays.asList(1, (byte) 2))));
        // 其余载体仍走十进制截断路径。
        assertEquals(3, registry.evaluate("to_int", List.of(3.9)));
        assertEquals(3L, registry.evaluate("to_bigint", List.of("3.9")));
        assertEquals("Expected numeric value, got: null", assertThrows(IllegalArgumentException.class,
                () -> registry.evaluate("to_int", java.util.Collections.singletonList(null))).getMessage());
    }

    private void assertSubOracle(Number left, Number right) {
        BigDecimal expected = BigDecimal.valueOf(left.longValue()).subtract(BigDecimal.valueOf(right.longValue()));
        long exact;
        try {
            exact = expected.longValueExact();
        } catch (ArithmeticException overflow) {
            assertEquals("sub overflow for result: " + expected, assertThrows(
                    IllegalArgumentException.class,
                    () -> registry.evaluate("sub", List.of(left, right))).getMessage());
            return;
        }
        Object actual = registry.evaluate("sub", List.of(left, right));
        assertEquals(Long.class, actual.getClass());
        assertEquals(Long.valueOf(exact), actual);
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof Double) return BigDecimal.valueOf((Double) value);
        if (value instanceof Float) return BigDecimal.valueOf(((Float) value).doubleValue());
        if (value instanceof BigDecimal) return (BigDecimal) value;
        if (value instanceof BigInteger) return new BigDecimal((BigInteger) value);
        return BigDecimal.valueOf(((Number) value).longValue());
    }
}
