package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.KeyedSequenceFilterSemantic;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorSequence;
import com.example.featuredag.operator.SequenceKeyDomains;

import java.util.List;

public final class ExtractIndustryOperator extends AbstractBuiltinOperator {
    public ExtractIndustryOperator() {
        super("extractIndustry", 2, 2, true, false, true, 1_000L,
                List.of(new KeyedSequenceFilterSemantic(0, 1, SequenceKeyDomains.INDUSTRY)));
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.fixedInference(inputs, DataType.EVENT_SEQUENCE, ValueShape.SEQUENCE);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        OperatorSequence sequence = OperatorSupport.asSequence(arguments.getFirst());
        String industry = String.valueOf(arguments.getLast());
        return sequence.filterByIndustry(industry);
    }
}
