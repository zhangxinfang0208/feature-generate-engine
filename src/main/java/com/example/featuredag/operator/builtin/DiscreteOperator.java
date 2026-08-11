package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;

import java.math.BigDecimal;
import java.util.List;

public final class DiscreteOperator extends AbstractBuiltinOperator {
    public DiscreteOperator() {
        super("discrete", 2, 2, true, true, false);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.fixedInference(inputs, DataType.INT, ValueShape.SCALAR);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        BigDecimal value = OperatorSupport.asPreciseDecimal(
                OperatorSupport.asNumber(arguments.get(0)),
                "discrete requires a finite numeric value");
        List<?> boundaries = OperatorSupport.asList(
                arguments.get(arguments.size() - 1), name(), "discrete_key");
        BigDecimal previous = null;
        int bucket = 0;
        for (int index = 0; index < boundaries.size(); index++) {
            Object boundary = boundaries.get(index);
            if (!(boundary instanceof Number)) {
                throw new IllegalArgumentException(
                        "discrete boundary at index " + index + " is not numeric: " + boundary);
            }
            BigDecimal current = OperatorSupport.asPreciseDecimal(
                    (Number) boundary,
                    "discrete boundary at index " + index + " must be finite");
            if (previous != null && current.compareTo(previous) <= 0) {
                throw new IllegalArgumentException(
                        "discrete boundaries must be strictly increasing at index " + index);
            }
            if (value.compareTo(current) >= 0) bucket++;
            previous = current;
        }
        return bucket;
    }
}
