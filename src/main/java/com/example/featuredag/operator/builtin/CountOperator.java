package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.SequenceCardinalitySemantic;
import com.example.featuredag.operator.OperatorSequence;

import java.util.Collection;
import java.util.List;

public final class CountOperator extends AbstractRowWiseBuiltinOperator {
    public CountOperator() {
        super("count", 1, 1, true, false, true, 10L,
                List.of(new SequenceCardinalitySemantic(0)));
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        OperatorInputMetadata input = inputs.getFirst();
        if (input.valueShape() != ValueShape.SEQUENCE
                && input.outputType() != DataType.OBJECT) {
            throw new IllegalArgumentException(
                    "count expects a sequence/collection input, got type="
                            + input.outputType() + ", shape=" + input.valueShape()
                            + " from " + input.sourceFeatureName());
        }
        return OperatorSupport.fixedInference(inputs, DataType.INT, ValueShape.SCALAR);
    }

    @Override
    protected Object evaluateRow(RowArguments arguments) {
        Object value = arguments.getFirst();
        if (value == null) return 0;
        if (value instanceof OperatorSequence sequence) return sequence.size();
        if (value instanceof Collection<?> collection) return collection.size();
        if (value.getClass().isArray()) return java.lang.reflect.Array.getLength(value);
        throw new IllegalArgumentException("count does not support: " + value.getClass());
    }
}
