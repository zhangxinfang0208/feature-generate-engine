package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.operator.OperatorResultList;
import com.example.featuredag.operator.OperatorSequence;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
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

    static OperatorInference elementWiseNumericInference(
            String operator,
            List<OperatorInputMetadata> inputs,
            DataType outputType) {
        boolean hasSequence = false;
        for (OperatorInputMetadata input : inputs) {
            if (input.outputType() == DataType.EVENT_SEQUENCE) {
                throw new IllegalArgumentException(
                        operator + " requires numeric input; event sequences are not"
                                + " supported (no implicit value projection)");
            }
            boolean unknownLiteralSequence = input.outputType() == DataType.OBJECT
                    && input.valueShape() == ValueShape.SEQUENCE;
            if (!input.outputType().isNumeric() && !unknownLiteralSequence) {
                throw new IllegalArgumentException(
                        operator + " requires numeric input, got: " + input.outputType());
            }
            if (input.valueShape() == ValueShape.SEQUENCE) {
                hasSequence = true;
            } else if (input.valueShape() != ValueShape.SCALAR
                    && input.valueShape() != ValueShape.CANDIDATE_VECTOR) {
                throw new IllegalArgumentException(
                        operator + " does not support value shape: " + input.valueShape());
            }
        }
        return fixedInference(
                inputs,
                outputType,
                hasSequence ? ValueShape.SEQUENCE : ValueShape.SCALAR);
    }

    static void requireNumericSequence(OperatorInputMetadata input, String operator) {
        // C6：字面量序列未携带元素类型，留到求值时逐元素验证。
        if (input.valueShape() != ValueShape.SEQUENCE
                || !(input.outputType().isNumeric() || input.outputType() == DataType.OBJECT
                        || input.outputType() == DataType.UNKNOWN)) {
            throw new IllegalArgumentException(operator + " expects a numeric sequence");
        }
    }

    static BigDecimal numericSequenceElement(Object sequence, int index, String operator) {
        try {
            return arithmeticOperand(sequenceElementAt(sequence, index, operator, "sequence"), operator);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(
                    operator + " failed at sequence index " + index + ": " + error.getMessage(), error);
        }
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
        // 错误文本只在失败时拼接，成功路径不分配字符串。
        BigDecimal decimal = toPreciseDecimal(asNumber(value));
        if (decimal == null) {
            throw new IllegalArgumentException(operator + " requires finite numeric values");
        }
        return decimal;
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

    /**
     * 整型载体的减法快路径：双方均可无损表示为 long 且差值不溢出时直接返回 Long；
     * 不满足条件或溢出时返回 null，由调用方回到精确十进制路径，保留原有的结果载体与溢出异常文本。
     */
    static Long subtractExactLong(Object left, Object right) {
        if (!isLongCarrier(left) || !isLongCarrier(right)) return null;
        try {
            return Long.valueOf(Math.subtractExact(
                    ((Number) left).longValue(), ((Number) right).longValue()));
        } catch (ArithmeticException overflow) {
            return null;
        }
    }

    static boolean isLongCarrier(Object value) {
        return value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long;
    }

    private static boolean isIntegralCarrier(Object value) {
        return isLongCarrier(value) || value instanceof BigInteger;
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

    /**
     * 整数精确转换；浮点数先验证有限性并采用十进制字符串语义，避免 new BigDecimal(double) 的二进制尾差。
     * 非有限或无法解析的数值返回 null，由调用方在失败分支拼接自己的错误文本。
     */
    /** 错误文本为常量的调用方使用；需要拼接下标的调用方改用 toPreciseDecimal，在失败分支拼接。 */
    static BigDecimal asPreciseDecimal(Number number, String errorMessage) {
        BigDecimal decimal = toPreciseDecimal(number);
        if (decimal == null) throw new IllegalArgumentException(errorMessage);
        return decimal;
    }

    static BigDecimal toPreciseDecimal(Number number) {
        if (number instanceof BigDecimal) return (BigDecimal) number;
        if (number instanceof BigInteger) return new BigDecimal((BigInteger) number);
        if (number instanceof Byte || number instanceof Short
                || number instanceof Integer || number instanceof Long) {
            return BigDecimal.valueOf(number.longValue());
        }
        if (number instanceof Float) {
            float floatValue = number.floatValue();
            if (!Float.isFinite(floatValue)) return null;
            return new BigDecimal(Float.toString(floatValue));
        }
        if (number instanceof Double) {
            double doubleValue = number.doubleValue();
            if (!Double.isFinite(doubleValue)) return null;
            return BigDecimal.valueOf(doubleValue);
        }
        try {
            return new BigDecimal(number.toString());
        } catch (NumberFormatException error) {
            return null;
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

    static Object evaluateElementWise(
            List<Object> arguments,
            String operator,
            Function<List<Object>, Object> scalarEvaluator) {
        int argumentCount = arguments.size();
        boolean[] sequenceArgument = new boolean[argumentCount];
        int sequenceSize = -1;
        for (int argumentIndex = 0; argumentIndex < argumentCount; argumentIndex++) {
            Object argument = arguments.get(argumentIndex);
            sequenceArgument[argumentIndex] = isSequence(argument);
            if (!sequenceArgument[argumentIndex]) continue;
            int currentSize = sequenceSize(argument);
            if (sequenceSize < 0) {
                sequenceSize = currentSize;
            } else if (currentSize != sequenceSize) {
                throw new IllegalArgumentException(
                        operator + " requires sequences of equal length; argument "
                                + argumentIndex + " has length " + currentSize
                                + ", expected " + sequenceSize);
            }
        }
        if (sequenceSize < 0) return scalarEvaluator.apply(arguments);

        // 整个调用只建一个参数列表，逐元素用 set 替换序列位置；
        // scalarEvaluator 只在本次调用内同步读取该列表，不得保存它。
        List<Object> scalarArguments = new ArrayList<Object>(arguments);
        List<Object> result = new ArrayList<Object>(sequenceSize);
        for (int sequenceIndex = 0; sequenceIndex < sequenceSize; sequenceIndex++) {
            try {
                for (int argumentIndex = 0; argumentIndex < argumentCount; argumentIndex++) {
                    if (sequenceArgument[argumentIndex]) {
                        scalarArguments.set(
                                argumentIndex,
                                sequenceElement(arguments.get(argumentIndex), sequenceIndex));
                    }
                }
                result.add(scalarEvaluator.apply(scalarArguments));
            } catch (RuntimeException error) {
                throw new IllegalArgumentException(
                        operator + " failed at sequence index " + sequenceIndex
                                + ": " + error.getMessage(),
                        error);
            }
        }
        return immutableList(result);
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

    /** 已由 isSequence 确认是序列的值：按逻辑下标读取，不做类型与参数名校验。 */
    private static int sequenceSize(Object sequence) {
        return sequence instanceof OperatorSequence
                ? ((OperatorSequence) sequence).size()
                : ((List<?>) sequence).size();
    }

    private static Object sequenceElement(Object sequence, int index) {
        return sequence instanceof OperatorSequence
                ? ((OperatorSequence) sequence).elementAt(index)
                : ((List<?>) sequence).get(index);
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
        // 可无损表示为 long 的载体无需构造 BigDecimal；Long 超出 int 范围时与精确路径报同一错误。
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Byte || value instanceof Short) return ((Number) value).intValue();
        if (value instanceof Long) {
            long longValue = (Long) value;
            if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(operator + " overflow for value: " + value);
            }
            return (int) longValue;
        }
        try {
            return truncatedDecimal(value, operator).intValueExact();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(operator + " overflow for value: " + value);
        }
    }

    static long truncatedLong(Object value, String operator) {
        if (isLongCarrier(value)) return ((Number) value).longValue();
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
            decimal = toPreciseDecimal(asNumber(value));
            if (decimal == null) {
                throw new IllegalArgumentException(operator + " requires a finite numeric value");
            }
        }
        // 把小数部分向零截断（与 SQL CAST 语义一致），不做四舍五入。
        return decimal.setScale(0, RoundingMode.DOWN);
    }

    private static IllegalArgumentException invalidCastValue(String operator, Object value) {
        return new IllegalArgumentException(
                operator + " requires a finite numeric value or decimal string, got: " + value);
    }

    /**
     * 按阈值筛选序列下标：元素与阈值均为整型载体时按 long 精确比较，不构造 BigDecimal；
     * 其余元素走精确十进制比较。smaller 为 true 保留小于阈值的下标，否则保留大于阈值的下标。
     */
    static List<Integer> indicesMatching(
            Object sequence, Object rawThreshold, boolean smaller, String operator) {
        int size = sequenceSize(sequence, operator, "sequence");
        BigDecimal threshold = arithmeticOperand(rawThreshold, operator);
        boolean integralThreshold = isLongCarrier(rawThreshold);
        long thresholdValue = integralThreshold ? ((Number) rawThreshold).longValue() : 0L;
        List<Integer> result = new ArrayList<Integer>();
        for (int index = 0; index < size; index++) {
            Object element = sequenceElementAt(sequence, index, operator, "sequence");
            int comparison;
            if (integralThreshold && isLongCarrier(element)) {
                comparison = Long.compare(((Number) element).longValue(), thresholdValue);
            } else {
                comparison = numericSequenceElement(sequence, index, operator).compareTo(threshold);
            }
            if (smaller ? comparison < 0 : comparison > 0) result.add(index);
        }
        return immutableList(result);
    }

    static Object selectExtreme(List<Object> values, boolean minimum, String operator) {
        // 全部为整型载体时按 long 精确比较，不构造 BigDecimal；相等时同样保留最左输入。
        boolean allLongCarriers = true;
        for (Object value : values) {
            if (!isLongCarrier(value)) {
                allLongCarriers = false;
                break;
            }
        }
        if (allLongCarriers) {
            Object integralWinner = null;
            long integralExtreme = 0L;
            for (Object value : values) {
                long current = ((Number) value).longValue();
                if (integralWinner == null
                        || (minimum ? current < integralExtreme : current > integralExtreme)) {
                    integralExtreme = current;
                    integralWinner = value;
                }
            }
            return integralWinner;
        }
        // 精确十进制比较消除 Integer/Long/Double 混比时的精度歧义；
        // 相等时保留最左输入，并返回胜出参数的原数值载体。
        BigDecimal extreme = null;
        Object winner = null;
        for (Object value : values) {
            BigDecimal decimal = arithmeticOperand(value, operator);
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

    /**
     * 冻结一个算子独占、之后不再修改的列表：只包装不复制。调用方传入的必须是本次调用新建的局部列表，
     * 不能是输入参数；输出边界凭 OperatorResultList 识别这类结果并直接透传。
     */
    static <T> List<T> immutableList(List<T> values) {
        return OperatorResultList.wrap(values);
    }

    /**
     * 批内按（请求组，单个对象身份）复用的缓存：外层按对象身份定位，内层按组下标直接寻址。
     * 命中时不分配任何对象，也没有 LinkedHashMap 节点和键对象的开销；只按身份比较，
     * 不对序列或配置集合做昂贵且语义不安全的深比较。
     * 仅在一次 Kernel 调用内使用；值必须非 null，可用各 Kernel 自己的哨兵表示“已见过”。
     */
    static final class IdentityBatchCache<T> {
        private final IdentityHashMap<Object, Object[]> groupsByIdentity =
                new IdentityHashMap<Object, Object[]>();

        @SuppressWarnings("unchecked")
        T get(int groupIndex, Object identity) {
            Object[] groups = groupsByIdentity.get(identity);
            int slot = slot(groupIndex);
            return groups == null || slot >= groups.length ? null : (T) groups[slot];
        }

        @SuppressWarnings("unchecked")
        void put(int groupIndex, Object identity, T value) {
            Object[] groups = groupsByIdentity.get(identity);
            int slot = slot(groupIndex);
            if (groups == null) {
                groups = new Object[slot + 1];
                groupsByIdentity.put(identity, groups);
            } else if (slot >= groups.length) {
                groups = Arrays.copyOf(groups, slot + 1);
                groupsByIdentity.put(identity, groups);
            }
            groups[slot] = value;
        }

        private static int slot(int groupIndex) {
            // 离线行等没有请求组的批使用 -1；偏移一位后非负，不能直接做数组下标。
            return groupIndex < 0 ? 0 : groupIndex + 1;
        }
    }
}
