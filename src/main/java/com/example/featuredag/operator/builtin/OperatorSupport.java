package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.BatchOperatorEvaluationException;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
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

    static OperatorInference passThroughInference(
            List<OperatorInputMetadata> inputs,
            int inputIndex) {
        OperatorInputMetadata input = inputs.get(inputIndex);
        return new OperatorInference(input.outputType(), unionScopes(inputs), input.valueShape());
    }

    private static Set<EntityScope> unionScopes(List<OperatorInputMetadata> inputs) {
        Set<EntityScope> result = new LinkedHashSet<EntityScope>();
        for (OperatorInputMetadata input : inputs) result.addAll(input.entityScopes());
        return result;
    }

    static Number asNumber(Object value) {
        if (value instanceof Number) return (Number) value;
        throw new IllegalArgumentException("Expected numeric value, got: " + value);
    }

    static BigDecimal asPreciseDecimal(Number number, String errorMessage) {
        if (number instanceof BigDecimal) return (BigDecimal) number;
        if (number instanceof BigInteger) return new BigDecimal((BigInteger) number);
        if (number instanceof Byte || number instanceof Short
                || number instanceof Integer || number instanceof Long) {
            return BigDecimal.valueOf(number.longValue());
        }
        if (number instanceof Float) {
            float floatValue = number.floatValue();
            if (!Float.isFinite(floatValue)) throw new IllegalArgumentException(errorMessage);
            return new BigDecimal(Float.toString(floatValue));
        }
        if (number instanceof Double) {
            double doubleValue = number.doubleValue();
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
        if (value instanceof List<?>) return (List<?>) value;
        throw new IllegalArgumentException(
                operator + " expects List for " + argument + ", got: " + typeName(value));
    }

    static int asSequenceIndex(
            Object value,
            int position,
            int sequenceSize,
            String operator) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(
                    operator + " index at position " + position + " is not numeric: " + value);
        }
        Number number = (Number) value;
        long longValue;
        try {
            if (number instanceof BigDecimal) {
                longValue = ((BigDecimal) number).longValueExact();
            } else if (number instanceof BigInteger) {
                longValue = ((BigInteger) number).longValueExact();
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

    static <T> List<T> immutableList(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }

    static BatchOperatorEvaluationException batchFailure(
            int rowIndex,
            RuntimeException error) {
        return new BatchOperatorEvaluationException(rowIndex, error);
    }

    static IdentityBatchKey identityBatchKey(int groupIndex, Object... identities) {
        return new IdentityBatchKey(groupIndex, identities);
    }

    static final class IdentityBatchKey {
        private final int groupIndex;
        private final Object[] identities;
        private final int hashCode;

        private IdentityBatchKey(int groupIndex, Object[] identities) {
            this.groupIndex = groupIndex;
            this.identities = identities.clone();
            int hash = groupIndex;
            for (Object identity : identities) {
                hash = 31 * hash + System.identityHashCode(identity);
            }
            this.hashCode = hash;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof IdentityBatchKey)) return false;
            IdentityBatchKey other = (IdentityBatchKey) value;
            if (groupIndex != other.groupIndex
                    || identities.length != other.identities.length) {
                return false;
            }
            for (int index = 0; index < identities.length; index++) {
                if (identities[index] != other.identities[index]) return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
