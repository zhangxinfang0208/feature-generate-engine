package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;

import java.util.List;

public final class LeastOperator extends AbstractRowWiseBuiltinOperator {
    public LeastOperator() {
        super("least", 2, Integer.MAX_VALUE, true, false, false);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        boolean allInt = true;
        for (OperatorInputMetadata input : inputs) {
            if (input.outputType() != DataType.INT) {
                allInt = false;
                break;
            }
        }
        return OperatorSupport.fixedInference(
                inputs, allInt ? DataType.INT : DataType.DOUBLE, ValueShape.SCALAR);
    }

    @Override
    protected Object evaluateRow(RowArguments arguments) {
        Number minimum = OperatorSupport.asNumber(arguments.getFirst());
        boolean returnDouble = OperatorSupport.isFloatingPoint(minimum);
        for (int index = 1; index < arguments.size(); index++) {
            Number candidate = OperatorSupport.asNumber(arguments.get(index));
            returnDouble |= OperatorSupport.isFloatingPoint(candidate);
            if (candidate.doubleValue() < minimum.doubleValue()) minimum = candidate;
        }
        if (returnDouble) return minimum.doubleValue();
        return minimum.intValue();
    }
}
