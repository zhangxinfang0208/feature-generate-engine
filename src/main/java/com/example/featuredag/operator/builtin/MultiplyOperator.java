package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;

import java.util.List;

public final class MultiplyOperator extends AbstractRowWiseBuiltinOperator {
    public MultiplyOperator() {
        super("multiply", 2, 2, true, false, false);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.fixedInference(inputs, DataType.DOUBLE, ValueShape.SCALAR);
    }

    @Override
    protected Object evaluateRow(RowArguments arguments) {
        return OperatorSupport.asNumber(arguments.getFirst()).doubleValue()
                * OperatorSupport.asNumber(arguments.getLast()).doubleValue();
    }
}
