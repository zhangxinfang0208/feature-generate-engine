package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class FindIndicesOperator extends AbstractBuiltinOperator {
    public FindIndicesOperator() {
        super("find_indices", 2, 2, true, false, true);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.fixedInference(inputs, DataType.INT, ValueShape.SEQUENCE);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        List<?> sequence = OperatorSupport.asList(arguments.getFirst(), name(), "sequence");
        Object target = arguments.getLast();
        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < sequence.size(); index++) {
            if (Objects.equals(sequence.get(index), target)) result.add(index);
        }
        return List.copyOf(result);
    }
}
