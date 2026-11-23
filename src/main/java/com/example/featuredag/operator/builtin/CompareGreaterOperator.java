package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.BorrowedArgumentsKernel;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;

import java.util.Arrays;
import java.util.List;

/** 返回严格大于阈值的全部零基逻辑下标，保持输入顺序。 */
public final class CompareGreaterOperator extends AbstractBuiltinOperator implements BorrowedArgumentsKernel {
    public CompareGreaterOperator() {
        super("compare_greater", 2, 2, true, true);
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
        return OperatorSupport.indicesMatching(arguments.get(0), arguments.get(1), false, name());
    }
}
