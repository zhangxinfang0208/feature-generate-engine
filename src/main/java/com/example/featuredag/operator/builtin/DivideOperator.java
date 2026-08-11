package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;

import java.util.List;

public final class DivideOperator extends AbstractRowWiseBuiltinOperator {
    public DivideOperator() {
        super("div", 2, 2, true, false, false);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.fixedInference(inputs, DataType.DOUBLE, ValueShape.SCALAR);
    }

    @Override
    protected Object evaluateRow(RowArguments arguments) {
        double divisor = OperatorSupport.asNumber(arguments.getLast()).doubleValue();
        if (divisor == 0.0) {
            throw new IllegalArgumentException("divisor must not be zero");
        }
        return OperatorSupport.asNumber(arguments.getFirst()).doubleValue() / divisor;
    }
}
