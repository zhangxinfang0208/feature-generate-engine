package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;

import java.util.List;

public final class LogBaseOperator extends AbstractBuiltinOperator {
    public LogBaseOperator() {
        super("log_base", 3, 3, true, false, false);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.fixedInference(inputs, DataType.DOUBLE, ValueShape.SCALAR);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        double value = OperatorSupport.finiteDouble(arguments.get(0), "log_base value");
        double base = OperatorSupport.finiteDouble(arguments.get(1), "log_base base");
        double upbound = OperatorSupport.finiteDouble(arguments.get(2), "log_base upbound");
        if (base <= 0.0 || base == 1.0) {
            throw new IllegalArgumentException(
                    "log_base base must be greater than zero and not equal to one");
        }
        if (value <= 0.0) {
            throw new IllegalArgumentException("log_base value must be greater than zero");
        }
        if (upbound <= 0.0) {
            throw new IllegalArgumentException("log_base upbound must be greater than zero");
        }
        return Math.log(Math.min(value, upbound)) / Math.log(base);
    }
}
