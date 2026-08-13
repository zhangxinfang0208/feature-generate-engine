package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.BatchOperatorEvaluationException;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.operator.OperatorSequence;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 首期内置算子共享的包内辅助方法：集中处理推断、数值校验、序列下标和批内身份键。 */
final class OperatorSupport {
    private OperatorSupport() {}

    static OperatorInference fixedInference(
            List<OperatorInputMetadata> inputs,
            DataType outputType,
            ValueShape valueShape) {
        // 输出类型/形状由算子固定，实体域仍取全部输入并集，使执行阶段能反映任一变化维度（C6/C10）。
        return new OperatorInference(outputType, unionScopes(inputs), valueShape);
    }

    static OperatorInference passThroughInference(
            List<OperatorInputMetadata> inputs,
            int inputIndex) {
        OperatorInputMetadata input = inputs.get(inputIndex);
        // 透传算子继承主输入的类型和形状，但实体域仍需包含其他参数的依赖域。
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
        // 整数精确转换；浮点数先验证有限性并采用十进制字符串语义，避免 new BigDecimal(double) 的二进制尾差。
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

    static int sequenceSize(Object value, String operator, String argument) {
        if (value instanceof OperatorSequence) {
            return ((OperatorSequence) value).size();
        }
        if (value instanceof List<?>) return ((List<?>) value).size();
        throw new IllegalArgumentException(
                operator + " expects a sequence for " + argument + ", got: " + typeName(value));
    }

    static Object sequenceElementAt(
            Object value,
            int index,
            String operator,
            String argument) {
        if (value instanceof OperatorSequence) {
            return ((OperatorSequence) value).elementAt(index);
        }
        if (value instanceof List<?>) return ((List<?>) value).get(index);
        throw new IllegalArgumentException(
                operator + " expects a sequence for " + argument + ", got: " + typeName(value));
    }

    static int asSequenceIndex(
            Object value,
            int position,
            int sequenceSize,
            String operator) {
        // 下标必须是可精确表示的非负整数，拒绝小数、非有限值、溢出和越界。
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
        // Native Batch Kernel 统一携带紧凑批行号，由 DagRuntime 再映射回原请求/候选位置。
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
            // 只按对象身份比较批内广播值，避免对序列或配置集合执行昂贵且语义不安全的深比较。
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
