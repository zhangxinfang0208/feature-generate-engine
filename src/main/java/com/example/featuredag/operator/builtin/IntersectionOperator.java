package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 两个基础类型序列求交集，去重并保留左侧首次出现的顺序与元素。 */
public final class IntersectionOperator extends AbstractBuiltinOperator {
    public IntersectionOperator() {
        super("intersection", 2, 2, true, true);
    }

    @Override
    public List<String> parameterNames() {
        return Arrays.asList("left", "right");
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        for (OperatorInputMetadata input : inputs) {
            DataType type = input.outputType();
            if (input.valueShape() != ValueShape.SEQUENCE
                    || !(type.isNumeric() || type == DataType.STRING || type == DataType.BOOLEAN)) {
                throw new IllegalArgumentException(
                        "intersection expects numeric, STRING or BOOLEAN sequences");
            }
        }
        DataType left = inputs.get(0).outputType();
        DataType right = inputs.get(1).outputType();
        if (left != right && !(left.isNumeric() && right.isNumeric())) {
            throw new IllegalArgumentException("intersection requires compatible element types");
        }
        // C6：结果是左序列的去重子集，类型不变；实体域包含双方依赖。
        return OperatorSupport.passThroughInference(inputs, 0);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        Object left = arguments.get(0);
        Object right = arguments.get(1);
        int leftSize = OperatorSupport.sequenceSize(left, name(), "left");
        int rightSize = OperatorSupport.sequenceSize(right, name(), "right");
        Set<Object> remaining = new HashSet<Object>();
        Class<?> kind = null;
        for (int index = 0; index < rightSize; index++) {
            Object value = OperatorSupport.sequenceElementAt(right, index, name(), "right");
            kind = validateKind(value, kind);
            remaining.add(key(value));
        }
        List<Object> result = new ArrayList<Object>();
        for (int index = 0; index < leftSize; index++) {
            Object value = OperatorSupport.sequenceElementAt(left, index, name(), "left");
            kind = validateKind(value, kind);
            if (remaining.remove(key(value))) result.add(value);
        }
        return OperatorSupport.immutableList(result);
    }

    private static Class<?> validateKind(Object value, Class<?> previous) {
        if (value == null) return previous;
        Class<?> current;
        if (value instanceof Number) current = Number.class;
        else if (value instanceof String) current = String.class;
        else if (value instanceof Boolean) current = Boolean.class;
        else throw new IllegalArgumentException(
                "intersection does not support element: " + OperatorSupport.typeName(value));
        if (previous != null && previous != current) {
            throw new IllegalArgumentException("intersection requires compatible element types");
        }
        return current;
    }

    private static Object key(Object value) {
        if (value instanceof Number) {
            return OperatorSupport.asPreciseDecimal(
                    (Number) value, "intersection requires finite numeric values").stripTrailingZeros();
        }
        return value;
    }
}
