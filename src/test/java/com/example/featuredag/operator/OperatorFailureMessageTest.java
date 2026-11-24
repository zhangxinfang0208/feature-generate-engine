package com.example.featuredag.operator;

import com.example.featuredag.operator.builtin.CalculateSequenceSubOperator;
import com.example.featuredag.operator.builtin.CompareGreaterOperator;
import com.example.featuredag.operator.builtin.CompareSmallerOperator;
import com.example.featuredag.operator.builtin.DiscreteOperator;
import com.example.featuredag.operator.builtin.MaxOperator;
import com.example.featuredag.operator.builtin.MeanOperator;
import com.example.featuredag.operator.builtin.MinOperator;
import com.example.featuredag.operator.builtin.SubOperator;
import com.example.featuredag.operator.builtin.ToBigintOperator;
import com.example.featuredag.operator.builtin.ToIntOperator;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;

/**
 * 固定数值算子在非法输入上的失败消息。成功路径不做字符串拼接、整型快路径等优化
 * 都不能改变这些文本，否则调用方看到的定位信息会漂移。
 */
public class OperatorFailureMessageTest {

    @Test
    public void nonFiniteAndNonNumericOperandsKeepTheirMessages() {
        assertMessage("sub requires finite numeric values", new SubOperator(), Double.NaN, 1);
        assertMessage("sub requires finite numeric values", new SubOperator(), 1, Double.NEGATIVE_INFINITY);
        assertMessage("sub failed at sequence index 1: sub requires finite numeric values",
                new SubOperator(), Arrays.asList(1, Double.POSITIVE_INFINITY), 1);
        assertMessage("sub failed at sequence index 2: Expected numeric value, got: x",
                new SubOperator(), Arrays.asList(1, 2, "x"), 1);
        assertMessage("Expected numeric value, got: null", new SubOperator(), null, 1);
        assertMessage("sub requires sequences of equal length; argument 1 has length 1, expected 2",
                new SubOperator(), Arrays.asList(1, 2), Collections.singletonList(1));
        assertMessage("min requires finite numeric values", new MinOperator(), 1, Double.NaN, 3);
        assertMessage("max requires finite numeric values", new MaxOperator(), 1, Double.NaN, 3);
        assertMessage("compare_smaller failed at sequence index 1: compare_smaller requires finite numeric values",
                new CompareSmallerOperator(), Arrays.asList(1, Double.NaN), 3);
        assertMessage("compare_greater failed at sequence index 0: Expected numeric value, got: x",
                new CompareGreaterOperator(), Arrays.asList("x", 2), 3);
        assertMessage("mean failed at sequence index 1: mean requires finite numeric values",
                new MeanOperator(), Arrays.asList(1, Double.NaN));
        assertMessage("calc_seq_sub failed at sequence index 1: calc_seq_sub requires finite numeric values",
                new CalculateSequenceSubOperator(), Arrays.asList(1, Double.NaN, 3));
    }

    @Test
    public void castsAndBoundariesKeepTheirMessages() {
        assertMessage("to_int requires a finite numeric value", new ToIntOperator(), Double.NaN);
        assertMessage("to_int requires a finite numeric value or decimal string, got: abc", new ToIntOperator(), "abc");
        assertMessage("to_int overflow for value: 3000000000", new ToIntOperator(), 3000000000L);
        assertMessage("to_int failed at sequence index 1: to_int requires a finite numeric value",
                new ToIntOperator(), Arrays.asList("1", Double.NaN));
        assertMessage("to_bigint requires a finite numeric value", new ToBigintOperator(), Double.POSITIVE_INFINITY);
        assertMessage("discrete boundary at index 1 must be finite", new DiscreteOperator(), 5, Arrays.asList(1, Double.NaN));
        assertMessage("discrete boundary at index 1 is not numeric: x", new DiscreteOperator(), 5, Arrays.asList(1, "x"));
        assertMessage("discrete requires a finite numeric value", new DiscreteOperator(), Double.NaN, Arrays.asList(1, 10));
    }

    @Test
    public void eachFailureStillProducesItsOwnExceptionInstance() {
        SubOperator operator = new SubOperator();
        RuntimeException first = assertThrows(IllegalArgumentException.class,
                () -> operator.evaluate(new ArrayList<>(Arrays.asList(Double.NaN, 1))));
        RuntimeException second = assertThrows(IllegalArgumentException.class,
                () -> operator.evaluate(new ArrayList<>(Arrays.asList(Double.NaN, 1))));

        assertEquals(first.getMessage(), second.getMessage());
        assertNotSame(first, second);
    }

    private static void assertMessage(String expected, OperatorDefinition operator, Object... arguments) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> operator.evaluate(new ArrayList<>(Arrays.asList(arguments))));
        assertEquals(expected, error.getMessage());
    }
}
