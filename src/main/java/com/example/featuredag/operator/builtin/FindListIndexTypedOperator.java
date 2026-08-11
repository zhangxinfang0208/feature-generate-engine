package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class FindListIndexTypedOperator extends AbstractBuiltinOperator {
    public FindListIndexTypedOperator() {
        super("find_list_index_typed", 2, 2, true, false, true);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.fixedInference(inputs, DataType.INT, ValueShape.SEQUENCE);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        List<?> sequence = OperatorSupport.asList(
                arguments.getFirst(), name(), "sequence");
        Object target = arguments.getLast();
        List<Integer> indices = new ArrayList<>();
        for (int index = 0; index < sequence.size(); index++) {
            if (Objects.equals(sequence.get(index), target)) indices.add(index);
        }
        return OperatorSupport.nullableImmutableList(indices);
    }
}
