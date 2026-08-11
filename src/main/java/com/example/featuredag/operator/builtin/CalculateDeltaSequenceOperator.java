package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;

import java.util.ArrayList;
import java.util.List;

public final class CalculateDeltaSequenceOperator extends AbstractBuiltinOperator {
    public CalculateDeltaSequenceOperator() {
        super("calc_delta_seq", 2, 2, true, false, true);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.fixedInference(inputs, DataType.DOUBLE, ValueShape.SEQUENCE);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        List<?> sequence = OperatorSupport.asList(arguments.getFirst(), name(), "sequence");
        double base = OperatorSupport.finiteDouble(arguments.getLast(), "calc_delta_seq base");
        List<Double> result = new ArrayList<>(sequence.size());
        for (int index = 0; index < sequence.size(); index++) {
            double value = OperatorSupport.finiteDouble(
                    sequence.get(index), "calc_delta_seq element at index " + index);
            result.add(value - base);
        }
        return List.copyOf(result);
    }
}
