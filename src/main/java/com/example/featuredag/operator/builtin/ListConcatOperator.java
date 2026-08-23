package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 将第二个序列的首元素广播到第一个序列，并逐元素拼接为字符串序列。 */
public final class ListConcatOperator extends AbstractBuiltinOperator {
    public ListConcatOperator() {
        super("list_concat", 2, 3, true, true);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        for (int index = 0; index < 2; index++) {
            if (inputs.get(index).valueShape() != ValueShape.SEQUENCE) {
                throw new IllegalArgumentException(
                        "list_concat expects sequence input at position " + index);
            }
            if (inputs.get(index).outputType() == DataType.EVENT_SEQUENCE) {
                throw new IllegalArgumentException(
                        "list_concat does not support event sequence elements");
            }
        }
        if (inputs.size() == 3
                && (inputs.get(2).outputType() != DataType.OBJECT
                        || inputs.get(2).valueShape() != ValueShape.OBJECT)) {
            throw new IllegalArgumentException(
                    "list_concat expects an object config as its third argument");
        }
        return OperatorSupport.fixedInference(inputs, DataType.STRING, ValueShape.SEQUENCE);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        Object sequence = arguments.get(0);
        Object suffixSequence = arguments.get(1);
        String delimiter = "#";
        if (arguments.size() == 3) {
            Object rawConfig = arguments.get(2);
            if (!(rawConfig instanceof Map<?, ?>)) {
                throw new IllegalArgumentException(
                        "list_concat expects an object config as its third argument");
            }
            Object configured = ((Map<?, ?>) rawConfig).get("delimiter");
            if (configured != null) delimiter = String.valueOf(configured);
        }
        int suffixSize = OperatorSupport.sequenceSize(
                suffixSequence, name(), "suffix sequence");
        if (suffixSize == 0) {
            throw new IllegalArgumentException(
                    "list_concat suffix sequence must not be empty");
        }
        Object suffix = OperatorSupport.sequenceElementAt(
                suffixSequence, 0, name(), "suffix sequence");
        if (suffix instanceof Map<?, ?>) {
            throw new IllegalArgumentException(
                    "list_concat does not support event suffix element");
        }
        int size = OperatorSupport.sequenceSize(sequence, name(), "sequence");
        List<String> result = new ArrayList<String>(size);
        for (int index = 0; index < size; index++) {
            Object element = OperatorSupport.sequenceElementAt(
                    sequence, index, name(), "sequence");
            if (element instanceof Map<?, ?>) {
                throw new IllegalArgumentException(
                        "list_concat does not support event element at index " + index);
            }
            result.add(String.valueOf(element) + delimiter + String.valueOf(suffix));
        }
        return OperatorSupport.immutableList(result);
    }
}
