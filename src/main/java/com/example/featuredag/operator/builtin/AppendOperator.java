package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.operator.OperatorSequence;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 将两个标量或序列按参数顺序合并为一个序列。 */
public final class AppendOperator extends AbstractBuiltinOperator {
    public AppendOperator() {
        super("append", 2, 2, true, true);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        validateInput(inputs.get(0), 0);
        validateInput(inputs.get(1), 1);
        DataType outputType = commonType(
                inputs.get(0).outputType(), inputs.get(1).outputType());
        // C6：append 固定产出序列，元素类型仅允许同型或数值安全提升。
        return OperatorSupport.fixedInference(inputs, outputType, ValueShape.SEQUENCE);
    }

    private static void validateInput(OperatorInputMetadata input, int index) {
        if (input.valueShape() != ValueShape.SCALAR
                && input.valueShape() != ValueShape.SEQUENCE) {
            throw new IllegalArgumentException(
                    "append expects SCALAR or SEQUENCE at argument " + index
                            + ", got shape=" + input.valueShape());
        }
        if (input.outputType() == DataType.OBJECT
                || input.outputType() == DataType.EVENT_SEQUENCE) {
            throw new IllegalArgumentException(
                    "append does not support " + input.outputType()
                            + " at argument " + index);
        }
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        Object left = arguments.get(0);
        Object right = arguments.get(1);
        int capacity = argumentSize(left) + argumentSize(right);
        List<Object> result = new ArrayList<Object>(capacity);
        appendArgument(result, left, "left");
        appendArgument(result, right, "right");
        normalizeCompatibleRuntimeTypes(result);
        return OperatorSupport.immutableList(result);
    }

    private static void normalizeCompatibleRuntimeTypes(List<Object> values) {
        int elementKind = 0;
        int numericWidth = 0;
        for (Object value : values) {
            if (value == null) continue;
            int valueKind;
            if (value instanceof Number) {
                valueKind = 1;
                numericWidth = Math.max(numericWidth, numericWidth((Number) value));
            } else if (value instanceof String) {
                valueKind = 2;
            } else if (value instanceof Boolean) {
                valueKind = 3;
            } else {
                throw new IllegalArgumentException(
                        "append does not support object element: " + value.getClass().getName());
            }
            if (elementKind == 0) {
                elementKind = valueKind;
            } else if (elementKind != valueKind) {
                throw new IllegalArgumentException(
                        "append requires compatible element types at runtime");
            }
        }
        if (elementKind != 1) return;
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            if (value == null) continue;
            Number number = (Number) value;
            if (numericWidth == 1) {
                if (!(value instanceof Integer)) {
                    values.set(index, Integer.valueOf(number.intValue()));
                }
            } else if (numericWidth == 2) {
                if (value instanceof BigInteger) {
                    try {
                        values.set(index, Long.valueOf(((BigInteger) value).longValueExact()));
                    } catch (ArithmeticException error) {
                        throw new IllegalArgumentException(
                                "append BIGINT element overflow: " + value);
                    }
                } else if (!(value instanceof Long)) {
                    values.set(index, Long.valueOf(number.longValue()));
                }
            } else if (!(value instanceof Double)) {
                values.set(index, Double.valueOf(number.doubleValue()));
            }
        }
    }

    private static int numericWidth(Number value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) return 1;
        if (value instanceof Long || value instanceof BigInteger) return 2;
        return 3;
    }

    private static int argumentSize(Object value) {
        if (value instanceof List<?>) return ((List<?>) value).size();
        if (value instanceof OperatorSequence) return ((OperatorSequence) value).size();
        return 1;
    }

    private static void appendArgument(List<Object> result, Object value, String argument) {
        if (!(value instanceof List<?>) && !(value instanceof OperatorSequence)) {
            if (value instanceof Map<?, ?>) {
                throw new IllegalArgumentException(
                        "append does not support object value in " + argument + " argument");
            }
            result.add(value);
            return;
        }
        int size = OperatorSupport.sequenceSize(value, "append", argument);
        for (int index = 0; index < size; index++) {
            Object element = OperatorSupport.sequenceElementAt(
                    value, index, "append", argument);
            if (element instanceof Map<?, ?>) {
                throw new IllegalArgumentException(
                        "append does not support object element in " + argument
                                + " argument at index " + index);
            }
            result.add(element);
        }
    }

    private static DataType commonType(DataType left, DataType right) {
        if (left == right) return left;
        if (left == DataType.UNKNOWN) return right;
        if (right == DataType.UNKNOWN) return left;
        if (left.isNumeric() && right.isNumeric()) {
            if (left == DataType.DOUBLE || right == DataType.DOUBLE) return DataType.DOUBLE;
            if (left == DataType.BIGINT || right == DataType.BIGINT) return DataType.BIGINT;
            return DataType.INT;
        }
        throw new IllegalArgumentException(
                "append requires compatible element types, got: " + left + " and " + right);
    }
}
