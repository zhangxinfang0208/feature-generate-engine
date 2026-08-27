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
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

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

    static void rejectEventSequence(String operator, List<OperatorInputMetadata> inputs) {
        // 事件序列不做隐式数值投影：构图期即拒绝（C6），运行时的数值检查为防御。
        for (OperatorInputMetadata input : inputs) {
            if (input.outputType() == DataType.EVENT_SEQUENCE) {
                throw new IllegalArgumentException(
                        operator + " requires numeric scalar input; event sequences are not"
                                + " supported (no implicit value projection)");
            }
        }
    }

    static OperatorInference numericCastInference(
            String operator,
            List<OperatorInputMetadata> inputs,
            DataType outputType) {
        OperatorInputMetadata input = inputs.get(0);
        if (input.outputType() == DataType.EVENT_SEQUENCE) {
            throw new IllegalArgumentException(
                    operator + " requires numeric input; event sequences are not"
                            + " supported (no implicit value projection)");
        }
        if (!input.outputType().isNumeric() && input.outputType() != DataType.STRING) {
            throw new IllegalArgumentException(
                    operator + " requires numeric or decimal-string input, got: "
                            + input.outputType());
        }

        ValueShape outputShape;
        if (input.valueShape() == ValueShape.SEQUENCE) {
            outputShape = ValueShape.SEQUENCE;
        } else if (input.valueShape() == ValueShape.SCALAR
                || input.valueShape() == ValueShape.CANDIDATE_VECTOR) {
            // Batch/候选维度不进入逻辑 ValueShape；Kernel 仍按行执行标量转换（C6/C10）。
            outputShape = ValueShape.SCALAR;
        } else {
            throw new IllegalArgumentException(
                    operator + " does not support value shape: " + input.valueShape());
        }
        return fixedInference(inputs, outputType, outputShape);
    }

    static DataType numericResultType(List<OperatorInputMetadata> inputs) {
        // 数值运算结果按 DOUBLE > BIGINT > INT 取宽度上界，与 C6 的安全提升方向一致。
        boolean hasBigint = false;
        for (OperatorInputMetadata input : inputs) {
            if (input.outputType() == DataType.DOUBLE) {
                return DataType.DOUBLE;
            }
            if (input.outputType() == DataType.BIGINT) hasBigint = true;
        }
        return hasBigint ? DataType.BIGINT : DataType.INT;
    }

    static BigDecimal arithmeticOperand(Object value, String operator) {
        // 算术操作数统一走精确十进制：先校验有限性，再消除 Integer/Long/Double 混算的精度歧义。
        return asPreciseDecimal(
                asNumber(value), operator + " requires finite numeric values");
    }

    static Object arithmeticCarrier(
            BigDecimal result, Object left, Object right, String operator) {
        // 精确十进制结果按输入载体定宽输出：双方均为整型载体 → Long（longValueExact
        // 溢出直接失败不回绕，与 to_bigint 的失败语义一致）；任一浮点载体 → Double
        // （超范围结果视为溢出，拒绝返回 ±Infinity）。
        if (isIntegralCarrier(left) && isIntegralCarrier(right)) {
            try {
                return Long.valueOf(result.longValueExact());
            } catch (ArithmeticException error) {
                throw new IllegalArgumentException(
                        operator + " overflow for result: " + result);
            }
        }
        double doubleResult = result.doubleValue();
        if (!Double.isFinite(doubleResult)) {
            throw new IllegalArgumentException(operator + " overflow for result: " + result);
        }
        return Double.valueOf(doubleResult);
    }

    private static boolean isIntegralCarrier(Object value) {
        return value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof BigInteger;
    }

    static double finiteQuotient(BigDecimal dividend, BigDecimal divisor, String operator) {
        // DECIMAL64（16 位有效数字）与 double 精度对齐；商超出 double 表示范围视为溢出。
        double result = dividend.divide(divisor, MathContext.DECIMAL64).doubleValue();
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException(operator + " overflow for quotient");
        }
        return result;
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

    static boolean isSequence(Object value) {
        return value instanceof OperatorSequence || value instanceof List<?>;
    }

    static <T> List<T> mapSequence(
            Object value,
            String operator,
            Function<Object, T> converter) {
        int size = sequenceSize(value, operator, "value");
        List<T> result = new ArrayList<T>(size);
        for (int index = 0; index < size; index++) {
            Object element = sequenceElementAt(value, index, operator, "value");
            try {
                result.add(converter.apply(element));
            } catch (RuntimeException error) {
                throw new IllegalArgumentException(
                        operator + " failed at sequence index " + index
                                + ": " + error.getMessage(),
                        error);
            }
        }
        return immutableList(result);
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

    static int truncatedInt(Object value, String operator) {
        try {
            return truncatedDecimal(value, operator).intValueExact();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(operator + " overflow for value: " + value);
        }
    }

    static long truncatedLong(Object value, String operator) {
        try {
            return truncatedDecimal(value, operator).longValueExact();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(operator + " overflow for value: " + value);
        }
    }

    private static BigDecimal truncatedDecimal(Object value, String operator) {
        // 显式转换算子额外接受十进制字符串，供外部平台只能把数值字段声明为 STRING 时使用；
        // 其他数值算子仍只接受 Number，避免在通用算术链路中引入隐式字符串转换。
        BigDecimal decimal;
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.isEmpty()) {
                throw invalidCastValue(operator, value);
            }
            try {
                decimal = new BigDecimal(text);
            } catch (NumberFormatException error) {
                throw invalidCastValue(operator, value);
            }
        } else {
            decimal = asPreciseDecimal(
                    asNumber(value), operator + " requires a finite numeric value");
        }
        // 把小数部分向零截断（与 SQL CAST 语义一致），不做四舍五入。
        return decimal.setScale(0, RoundingMode.DOWN);
    }

    private static IllegalArgumentException invalidCastValue(String operator, Object value) {
        return new IllegalArgumentException(
                operator + " requires a finite numeric value or decimal string, got: " + value);
    }

    static Object selectExtreme(List<Object> values, boolean minimum, String operator) {
        // 精确十进制比较消除 Integer/Long/Double 混比时的精度歧义；
        // 相等时保留最左输入，并返回胜出参数的原数值载体。
        BigDecimal extreme = null;
        Object winner = null;
        for (Object value : values) {
            BigDecimal decimal = asPreciseDecimal(
                    asNumber(value), operator + " requires finite numeric values");
            if (extreme == null
                    || (minimum
                            ? decimal.compareTo(extreme) < 0
                            : decimal.compareTo(extreme) > 0)) {
                extreme = decimal;
                winner = value;
            }
        }
        return winner;
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
