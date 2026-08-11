package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class GreaterInSequenceTypedOperator extends AbstractBuiltinOperator {
    public GreaterInSequenceTypedOperator() {
        super("greater_in_sequence_typed", 3, 3, true, true, true);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.fixedInference(inputs, DataType.INT, ValueShape.SEQUENCE);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        List<?> sequence = OperatorSupport.asList(arguments.get(0), name(), "sequence");
        Number base = OperatorSupport.asNumber(arguments.get(1));
        BigDecimal baseValue = OperatorSupport.asPreciseDecimal(
                base, name() + " requires finite numeric base");
        Map<?, ?> config = OperatorSupport.asMap(arguments.get(2));
        Object marginValue = config.get("margin");
        if (!(marginValue instanceof Number marginNumber)) {
            throw new IllegalArgumentException(name() + " requires numeric margin");
        }
        BigDecimal margin = OperatorSupport.asPreciseDecimal(
                marginNumber, name() + " margin must be finite and non-negative");
        if (margin.signum() < 0) {
            throw new IllegalArgumentException(
                    name() + " margin must be finite and non-negative");
        }
        BigDecimal threshold = baseValue.subtract(margin);
        List<Integer> indices = new ArrayList<>();
        for (int index = 0; index < sequence.size(); index++) {
            Object element = sequence.get(index);
            if (!(element instanceof Number number)) {
                throw new IllegalArgumentException(
                        name() + " requires numeric element at index " + index);
            }
            BigDecimal elementValue = OperatorSupport.asPreciseDecimal(
                    number, name() + " requires numeric element at index " + index);
            if (elementValue.compareTo(threshold) > 0) indices.add(index);
        }
        return OperatorSupport.nullableImmutableList(indices);
    }
}
