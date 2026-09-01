package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;

import java.util.List;
import java.util.Map;

/** 使用可选分隔符把序列元素拼接为一个字符串。 */
public final class JoinOperator extends AbstractBuiltinOperator {
    private static final String DEFAULT_DELIMITER = "#";

    public JoinOperator() {
        super("join", 1, 2, true, true);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        OperatorInputMetadata sequence = inputs.get(0);
        if (sequence.valueShape() != ValueShape.SEQUENCE) {
            throw new IllegalArgumentException(
                    "join expects a sequence as its first argument");
        }
        if (sequence.outputType() == DataType.EVENT_SEQUENCE) {
            throw new IllegalArgumentException(
                    "join does not support event sequence elements");
        }
        if (sequence.outputType() == DataType.OBJECT) {
            throw new IllegalArgumentException(
                    "join does not support object sequence elements");
        }
        if (inputs.size() == 2) {
            OperatorInputMetadata delimiter = inputs.get(1);
            if (delimiter.outputType() != DataType.STRING
                    || delimiter.valueShape() != ValueShape.SCALAR) {
                throw new IllegalArgumentException(
                        "join expects a string scalar delimiter as its second argument");
            }
        }
        // C6：序列折叠为 STRING/SCALAR，实体域仍由所有输入共同决定。
        return OperatorSupport.fixedInference(inputs, DataType.STRING, ValueShape.SCALAR);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        Object sequence = arguments.get(0);
        String delimiter = DEFAULT_DELIMITER;
        if (arguments.size() == 2) {
            Object rawDelimiter = arguments.get(1);
            if (!(rawDelimiter instanceof String)) {
                throw new IllegalArgumentException(
                        "join expects a string delimiter as its second argument");
            }
            delimiter = (String) rawDelimiter;
        }
        int size = OperatorSupport.sequenceSize(sequence, name(), "sequence");
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < size; index++) {
            Object element = OperatorSupport.sequenceElementAt(
                    sequence, index, name(), "sequence");
            if (element instanceof Map<?, ?>) {
                throw new IllegalArgumentException(
                        "join does not support object element at index " + index);
            }
            if (index > 0) result.append(delimiter);
            result.append(String.valueOf(element));
        }
        return result.toString();
    }
}
