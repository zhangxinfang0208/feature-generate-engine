package com.example.featuredag.operator.builtin;

import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.operator.OperatorInference;

import java.util.List;

public final class CoalesceOperator extends AbstractBuiltinOperator {
    public CoalesceOperator() {
        super("coalesce", 1, Integer.MAX_VALUE, true, false, true);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        OperatorInputMetadata first = inputs.getFirst();
        return new OperatorInference(
                first.outputType(), OperatorSupport.unionScopes(inputs), first.valueShape());
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        for (Object argument : arguments) {
            if (argument != null) return argument;
        }
        return null;
    }
}
