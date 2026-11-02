package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collections;
import java.util.List;

/** 数值序列的算术平均值；精确累加后求商，输出有限 DOUBLE，空序列报错。 */
public final class MeanOperator extends AbstractBuiltinOperator {
    public MeanOperator() {
        super("mean", 1, 1, true, true);
    }

    @Override
    public List<String> parameterNames() {
        return Collections.singletonList("sequence");
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        OperatorSupport.requireNumericSequence(inputs.get(0), name());
        return OperatorSupport.fixedInference(inputs, DataType.DOUBLE, ValueShape.SCALAR);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        Object sequence = arguments.get(0);
        int size = OperatorSupport.sequenceSize(sequence, name(), "sequence");
        if (size == 0) throw new IllegalArgumentException("mean requires a non-empty sequence");
        BigDecimal sum = BigDecimal.ZERO;
        for (int index = 0; index < size; index++) {
            sum = sum.add(OperatorSupport.numericSequenceElement(sequence, index, name()));
        }
        // 保留足够有效位后转换为 double，避免有限均值因中间求和溢出或过早舍入而失败。
        double result = sum.divide(BigDecimal.valueOf(size), MathContext.DECIMAL128).doubleValue();
        if (!Double.isFinite(result)) throw new IllegalArgumentException("mean overflow for result");
        return Double.valueOf(result);
    }
}
