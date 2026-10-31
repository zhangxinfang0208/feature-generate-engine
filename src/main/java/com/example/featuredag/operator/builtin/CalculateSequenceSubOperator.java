package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 按逻辑顺序计算后一个元素减前一个元素，空序列和单元素序列返回空序列。 */
public final class CalculateSequenceSubOperator extends AbstractBuiltinOperator {
    public CalculateSequenceSubOperator() {
        super("calc_seq_sub", 1, 1, true, true);
    }

    @Override
    public List<String> parameterNames() {
        return Collections.singletonList("sequence");
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        OperatorSupport.requireNumericSequence(inputs.get(0), name());
        DataType type = inputs.get(0).outputType();
        return OperatorSupport.fixedInference(inputs,
                type == DataType.OBJECT ? DataType.UNKNOWN : type, ValueShape.SEQUENCE);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        Object sequence = arguments.get(0);
        int size = OperatorSupport.sequenceSize(sequence, name(), "sequence");
        List<Object> result = new ArrayList<Object>(Math.max(0, size - 1));
        Object previous = null;
        BigDecimal previousDecimal = null;
        for (int index = 0; index < size; index++) {
            Object current = OperatorSupport.sequenceElementAt(sequence, index, name(), "sequence");
            try {
                BigDecimal currentDecimal = OperatorSupport.arithmeticOperand(current, name());
                if (index > 0) {
                    result.add(OperatorSupport.arithmeticCarrier(
                            currentDecimal.subtract(previousDecimal), current, previous, name()));
                }
                previousDecimal = currentDecimal;
                previous = current;
            } catch (RuntimeException error) {
                throw new IllegalArgumentException(
                        name() + " failed at sequence index " + index + ": " + error.getMessage(), error);
            }
        }
        return OperatorSupport.immutableList(result);
    }
}
