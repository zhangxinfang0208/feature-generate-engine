package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.operator.OperatorSequence;

import java.util.List;
import java.util.Map;

/** 拼接两个或更多标量值，可用末尾对象字面量覆盖分隔符。 */
public final class ConcatOperator extends AbstractBuiltinOperator {
    private static final String DEFAULT_DELIMITER = "#";

    public ConcatOperator() {
        super("concat", 2, Integer.MAX_VALUE, true, false);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        int valueCount = valueCount(inputs);
        if (valueCount < 2) {
            throw new IllegalArgumentException("concat requires at least two scalar values");
        }
        for (int index = 0; index < valueCount; index++) {
            OperatorInputMetadata input = inputs.get(index);
            if (input.valueShape() != ValueShape.SCALAR
                    || input.outputType() == DataType.OBJECT
                    || input.outputType() == DataType.EVENT_SEQUENCE) {
                throw new IllegalArgumentException(
                        "concat expects scalar value at argument " + index
                                + ", got shape=" + input.valueShape()
                                + ", type=" + input.outputType());
            }
        }
        // C6：标量拼接固定产出 STRING/SCALAR，实体域取全部值参数与配置参数的并集。
        return OperatorSupport.fixedInference(inputs, DataType.STRING, ValueShape.SCALAR);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        int valueCount = arguments.size();
        String delimiter = DEFAULT_DELIMITER;
        Object last = arguments.get(arguments.size() - 1);
        // 与 zip_concat 保持一致：只有末尾 Map 才解释为配置，其余位置的 Map 一律非法。
        if (last instanceof Map<?, ?>) {
            valueCount--;
            Object configured = ((Map<?, ?>) last).get("delimiter");
            if (configured != null) delimiter = String.valueOf(configured);
        }
        if (valueCount < 2) {
            throw new IllegalArgumentException("concat requires at least two scalar values");
        }

        StringBuilder result = new StringBuilder();
        for (int index = 0; index < valueCount; index++) {
            Object value = arguments.get(index);
            if (value instanceof List<?> || value instanceof OperatorSequence) {
                throw new IllegalArgumentException(
                        "concat expects scalar value at argument " + index
                                + ", got sequence");
            }
            if (value instanceof Map<?, ?>) {
                throw new IllegalArgumentException(
                        "concat does not support object value at argument " + index);
            }
            if (index > 0) result.append(delimiter);
            result.append(String.valueOf(value));
        }
        return result.toString();
    }

    private static int valueCount(List<OperatorInputMetadata> inputs) {
        int count = inputs.size();
        if (inputs.get(count - 1).valueShape() == ValueShape.OBJECT) count--;
        return count;
    }
}
