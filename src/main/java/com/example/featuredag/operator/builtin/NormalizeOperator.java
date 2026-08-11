package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;

import java.util.List;
import java.util.Map;

public final class NormalizeOperator extends AbstractBuiltinOperator {
    public NormalizeOperator() {
        super("normalize", 2, 2, true, true, false);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.fixedInference(inputs, DataType.DOUBLE, ValueShape.SCALAR);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        if (arguments.getFirst() == null) return null;
        double value = OperatorSupport.asNumber(arguments.getFirst()).doubleValue();
        Map<?, ?> params = OperatorSupport.asMap(arguments.getLast());
        double min = OperatorSupport.getDouble(params, "min", 0.0);
        double max = OperatorSupport.getDouble(params, "max", 1.0);
        if (max == min) return 0.0;
        return (value - min) / (max - min);
    }
}
