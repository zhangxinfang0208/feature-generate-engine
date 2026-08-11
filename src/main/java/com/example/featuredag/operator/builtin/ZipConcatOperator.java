package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ZipConcatOperator extends AbstractBuiltinOperator {
    public ZipConcatOperator() {
        super("zip_concat", 2, Integer.MAX_VALUE, true, true, true);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.fixedInference(inputs, DataType.STRING, ValueShape.SEQUENCE);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        int sequenceCount = arguments.size();
        String delimiter = "#";
        Object last = arguments.get(arguments.size() - 1);
        if (last instanceof Map<?, ?>) {
            sequenceCount--;
            Map<?, ?> config = (Map<?, ?>) last;
            Object configured = config.get("delimiter");
            if (configured != null) delimiter = String.valueOf(configured);
        }
        if (sequenceCount < 2) {
            throw new IllegalArgumentException("zip_concat requires at least two sequences");
        }
        List<List<?>> sequences = new ArrayList<List<?>>(sequenceCount);
        int size = -1;
        for (int index = 0; index < sequenceCount; index++) {
            List<?> sequence = OperatorSupport.asList(
                    arguments.get(index), name(), "sequence " + index);
            if (size < 0) size = sequence.size();
            if (sequence.size() != size) {
                throw new IllegalArgumentException(
                        "zip_concat requires sequences of equal length; sequence " + index
                                + " has length " + sequence.size() + ", expected " + size);
            }
            sequences.add(sequence);
        }
        List<String> result = new ArrayList<String>(size);
        for (int row = 0; row < size; row++) {
            StringBuilder joined = new StringBuilder();
            for (int column = 0; column < sequences.size(); column++) {
                if (column > 0) joined.append(delimiter);
                joined.append(String.valueOf(sequences.get(column).get(row)));
            }
            result.add(joined.toString());
        }
        return OperatorSupport.immutableList(result);
    }
}
