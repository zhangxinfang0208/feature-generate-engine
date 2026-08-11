package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorSequence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

public final class CountDistinctOperator extends AbstractBuiltinOperator {
    public CountDistinctOperator() {
        super("count_distinct", 1, 1, true, false, false);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.fixedInference(inputs, DataType.INT, ValueShape.SCALAR);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        Object sequence = arguments.getFirst();
        Collection<?> values;
        if (sequence instanceof OperatorSequence value) {
            List<Object> events = new ArrayList<>(value.size());
            for (int index = 0; index < value.size(); index++) {
                events.add(value.elementAt(index));
            }
            values = events;
        } else if (sequence instanceof Collection<?> collection) {
            values = collection;
        } else {
            throw new IllegalArgumentException(
                    "count_distinct expects a sequence, got: "
                            + OperatorSupport.typeName(sequence));
        }
        return new LinkedHashSet<>(values).size();
    }
}
