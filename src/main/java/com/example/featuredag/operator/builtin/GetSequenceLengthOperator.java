package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorSequence;

import java.util.Collection;
import java.util.List;

public final class GetSequenceLengthOperator extends AbstractBuiltinOperator {
    public GetSequenceLengthOperator() {
        super("get_seq_length", 1, 1, true, false, false);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.fixedInference(inputs, DataType.INT, ValueShape.SCALAR);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        Object sequence = arguments.get(0);
        if (sequence instanceof OperatorSequence) return ((OperatorSequence) sequence).size();
        if (sequence instanceof Collection<?>) return ((Collection<?>) sequence).size();
        if (sequence != null && sequence.getClass().isArray()) {
            return java.lang.reflect.Array.getLength(sequence);
        }
        throw new IllegalArgumentException(
                "get_seq_length expects a sequence, got: " + OperatorSupport.typeName(sequence));
    }
}
