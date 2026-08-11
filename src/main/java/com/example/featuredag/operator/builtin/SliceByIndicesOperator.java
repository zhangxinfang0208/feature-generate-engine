package com.example.featuredag.operator.builtin;

import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.operator.OperatorInference;

import java.util.ArrayList;
import java.util.List;

public final class SliceByIndicesOperator extends AbstractBuiltinOperator {
    public SliceByIndicesOperator() {
        super("slice_by_indices", 2, 2, true, true, true);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.passThroughInference(inputs, 0);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        List<?> sequence = OperatorSupport.asList(arguments.get(0), name(), "sequence");
        List<?> indices = OperatorSupport.asList(
                arguments.get(arguments.size() - 1), name(), "indices");
        List<Object> result = new ArrayList<Object>(indices.size());
        for (int position = 0; position < indices.size(); position++) {
            int index = OperatorSupport.asSequenceIndex(
                    indices.get(position), position, sequence.size(), name());
            result.add(sequence.get(index));
        }
        return OperatorSupport.immutableList(result);
    }
}
