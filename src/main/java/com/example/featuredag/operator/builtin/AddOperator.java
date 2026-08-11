package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;

import java.util.List;

public final class AddOperator extends AbstractRowWiseBuiltinOperator {
    public AddOperator() {
        super("add", 2, Integer.MAX_VALUE, true, false, false);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.fixedInference(inputs, DataType.DOUBLE, ValueShape.SCALAR);
    }

    @Override
    protected Object evaluateRow(RowArguments arguments) {
        double result = 0.0;
        for (int index = 0; index < arguments.size(); index++) {
            result += OperatorSupport.asNumber(arguments.get(index)).doubleValue();
        }
        return result;
    }
}
