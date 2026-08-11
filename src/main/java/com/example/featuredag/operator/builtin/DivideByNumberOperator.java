package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;

import java.util.List;
import java.util.Map;

public final class DivideByNumberOperator extends AbstractRowWiseBuiltinOperator {
    public DivideByNumberOperator() {
        super("div_num", 2, 2, true, true, false);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.fixedInference(inputs, DataType.DOUBLE, ValueShape.SCALAR);
    }

    @Override
    protected Object evaluateRow(RowArguments arguments) {
        double value = OperatorSupport.asNumber(arguments.getFirst()).doubleValue();
        Map<?, ?> params = OperatorSupport.asMap(arguments.getLast());
        double divisor = OperatorSupport.getDouble(params, "divisor", 1.0);
        if (divisor == 0.0) {
            throw new IllegalArgumentException("divisor must not be zero");
        }
        return value / divisor;
    }
}
