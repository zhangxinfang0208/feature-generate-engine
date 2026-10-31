package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 返回严格小于阈值的全部零基逻辑下标，保持输入顺序。 */
public final class CompareSmallerOperator extends AbstractBuiltinOperator {
    public CompareSmallerOperator() {
        super("compare_smaller", 2, 2, true, true);
    }

    @Override
    public List<String> parameterNames() {
        return Arrays.asList("sequence", "value");
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        OperatorSupport.requireNumericSequence(inputs.get(0), name());
        OperatorInputMetadata value = inputs.get(1);
        if ((!value.outputType().isNumeric() && value.outputType() != DataType.UNKNOWN)
                || (value.valueShape() != ValueShape.SCALAR
                        && value.valueShape() != ValueShape.CANDIDATE_VECTOR)) {
            throw new IllegalArgumentException(name() + " expects a numeric scalar threshold");
        }
        return OperatorSupport.fixedInference(inputs, DataType.INT, ValueShape.SEQUENCE);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        Object sequence = arguments.get(0);
        int size = OperatorSupport.sequenceSize(sequence, name(), "sequence");
        BigDecimal threshold = OperatorSupport.arithmeticOperand(arguments.get(1), name());
        List<Integer> result = new ArrayList<Integer>();
        for (int index = 0; index < size; index++) {
            if (OperatorSupport.numericSequenceElement(sequence, index, name()).compareTo(threshold) < 0) {
                result.add(index);
            }
        }
        return OperatorSupport.immutableList(result);
    }
}
