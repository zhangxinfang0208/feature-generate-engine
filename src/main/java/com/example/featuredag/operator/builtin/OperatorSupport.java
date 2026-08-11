package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorSequence;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Package-local value and inference helpers shared by built-in operators. */
final class OperatorSupport {
    private OperatorSupport() {}

    static OperatorInference fixedInference(
            List<OperatorInputMetadata> inputs,
            DataType outputType,
            ValueShape valueShape) {
        return new OperatorInference(outputType, unionScopes(inputs), valueShape);
    }

    static OperatorInference passThroughInference(List<OperatorInputMetadata> inputs, int inputIndex) {
        OperatorInputMetadata input = inputs.get(inputIndex);
        return new OperatorInference(input.outputType(), unionScopes(inputs), input.valueShape());
    }

    static Set<EntityScope> unionScopes(List<OperatorInputMetadata> inputs) {
        Set<EntityScope> result = new LinkedHashSet<>();
        for (OperatorInputMetadata input : inputs) result.addAll(input.entityScopes());
        return result;
    }

    static Number asNumber(Object value) {
        if (value instanceof Number number) return number;
        throw new IllegalArgumentException("Expected numeric value, got: " + value);
    }

    static boolean isFloatingPoint(Number value) {
        return value instanceof Float
                || value instanceof Double
                || value instanceof BigDecimal;
    }

    static Map<?, ?> asMap(Object value) {
        if (value instanceof Map<?, ?> map) return map;
        throw new IllegalArgumentException("Expected object/map, got: " + value);
    }

    static OperatorSequence asSequence(Object value) {
        if (value instanceof OperatorSequence sequence) return sequence;
        throw new IllegalArgumentException("Expected operator sequence, got: " + value);
    }

    static BigDecimal asPreciseDecimal(Number number, String errorMessage) {
        if (number instanceof BigDecimal decimal) return decimal;
        if (number instanceof BigInteger integer) return new BigDecimal(integer);
        if (number instanceof Byte || number instanceof Short
                || number instanceof Integer || number instanceof Long) {
            return BigDecimal.valueOf(number.longValue());
        }
        if (number instanceof Float floatValue) {
            if (!Float.isFinite(floatValue)) throw new IllegalArgumentException(errorMessage);
            return new BigDecimal(Float.toString(floatValue));
        }
        if (number instanceof Double doubleValue) {
            if (!Double.isFinite(doubleValue)) throw new IllegalArgumentException(errorMessage);
            return BigDecimal.valueOf(doubleValue);
        }
        try {
            return new BigDecimal(number.toString());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(errorMessage);
        }
    }

    static List<?> asList(Object value, String operator, String argument) {
        if (value instanceof List<?> list) return list;
        throw new IllegalArgumentException(
                operator + " expects List for " + argument + ", got: " + typeName(value));
    }

    static int asSequenceIndex(
            Object value,
            int position,
            int sequenceSize,
            String operator) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(
                    operator + " index at position " + position + " is not numeric: " + value);
        }
        long longValue;
        try {
            if (number instanceof BigDecimal decimal) {
                longValue = decimal.longValueExact();
            } else if (number instanceof BigInteger integer) {
                longValue = integer.longValueExact();
            } else {
                double doubleValue = number.doubleValue();
                longValue = number.longValue();
                if (!Double.isFinite(doubleValue) || doubleValue != longValue) {
                    throw outOfBounds(operator, position, value, sequenceSize);
                }
            }
        } catch (ArithmeticException error) {
            throw outOfBounds(operator, position, value, sequenceSize);
        }
        if (longValue < 0 || longValue >= sequenceSize) {
            throw outOfBounds(operator, position, value, sequenceSize);
        }
        return (int) longValue;
    }

    private static IllegalArgumentException outOfBounds(
            String operator,
            int position,
            Object value,
            int sequenceSize) {
        return new IllegalArgumentException(
                operator + " index at position " + position
                        + " is out of bounds: " + value + ", size=" + sequenceSize);
    }

    static double finiteDouble(Object value, String argument) {
        double result = asNumber(value).doubleValue();
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException(argument + " must be finite");
        }
        return result;
    }

    static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    static <T> List<T> nullableImmutableList(List<T> values) {
        return java.util.Collections.unmodifiableList(new ArrayList<>(values));
    }

    static double getDouble(Map<?, ?> params, String key, double defaultValue) {
        Object value = params.get(key);
        return value == null ? defaultValue : asNumber(value).doubleValue();
    }
}
