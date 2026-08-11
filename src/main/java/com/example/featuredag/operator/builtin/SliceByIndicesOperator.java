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
        List<?> sequence = OperatorSupport.asList(arguments.getFirst(), name(), "sequence");
        List<?> indices = OperatorSupport.asList(arguments.getLast(), name(), "indices");
        List<Object> result = new ArrayList<>(indices.size());
        for (int position = 0; position < indices.size(); position++) {
            int index = OperatorSupport.asSequenceIndex(
                    indices.get(position), position, sequence.size(), name());
            result.add(sequence.get(index));
        }
        return OperatorSupport.nullableImmutableList(result);
    }
}
