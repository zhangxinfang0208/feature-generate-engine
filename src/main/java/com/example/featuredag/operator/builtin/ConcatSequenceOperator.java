package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 将两个基础类型序列按参数顺序连接，保留重复项、null 与原始元素载体。 */
public final class ConcatSequenceOperator extends AbstractBuiltinOperator {
    public ConcatSequenceOperator() {
        super("concat_seq", 2, 2, true, true);
    }

    @Override
    public List<String> parameterNames() {
        return Arrays.asList("left", "right");
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        for (OperatorInputMetadata input : inputs) {
            if (input.valueShape() != ValueShape.SEQUENCE || input.outputType() == DataType.EVENT_SEQUENCE) {
                throw new IllegalArgumentException("concat_seq expects sequences of scalar elements");
            }
        }
        DataType left = inputs.get(0).outputType();
        DataType right = inputs.get(1).outputType();
        DataType result;
        if (left == DataType.OBJECT || right == DataType.OBJECT
                || left == DataType.UNKNOWN || right == DataType.UNKNOWN) {
            result = DataType.UNKNOWN;
        } else if (left == right) {
            result = left;
        } else if (left.isNumeric() && right.isNumeric()) {
            result = OperatorSupport.numericResultType(inputs);
        } else {
            throw new IllegalArgumentException("concat_seq requires compatible element types");
        }
        // C6：有类型序列仅同型或数值安全提升，实体域包含双方依赖。
        return OperatorSupport.fixedInference(inputs, result, ValueShape.SEQUENCE);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        List<Object> result = new ArrayList<Object>();
        Class<?> kind = null;
        for (Object sequence : arguments) {
            int size = OperatorSupport.sequenceSize(sequence, name(), "sequence");
            for (int index = 0; index < size; index++) {
                Object value = OperatorSupport.sequenceElementAt(sequence, index, name(), "sequence");
                if (value != null) {
                    Class<?> current;
                    if (value instanceof Number) current = Number.class;
                    else if (value instanceof String) current = String.class;
                    else if (value instanceof Boolean) current = Boolean.class;
                    else throw new IllegalArgumentException("concat_seq expects scalar elements at index " + index);
                    if (kind != null && kind != current) {
                        throw new IllegalArgumentException("concat_seq requires compatible element types");
                    }
                    kind = current;
                }
                result.add(value);
            }
        }
        return OperatorSupport.immutableList(result);
    }
}
