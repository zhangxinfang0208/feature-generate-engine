package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;

import java.util.List;
import java.util.Locale;

/** 字符串标量或序列逐元素转小写；使用固定语言规则，Batch 由逐行适配器执行。 */
public final class LowerOperator extends AbstractBuiltinOperator {
    public LowerOperator() {
        super("lower", 1, 1, true, true);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        OperatorInputMetadata input = inputs.get(0);
        if (input.outputType() != DataType.STRING) {
            throw new IllegalArgumentException("lower requires STRING input, got: " + input.outputType());
        }
        ValueShape shape = input.valueShape();
        if (shape != ValueShape.SCALAR && shape != ValueShape.SEQUENCE
                && shape != ValueShape.CANDIDATE_VECTOR) {
            throw new IllegalArgumentException("lower does not support value shape: " + shape);
        }
        // C6/C10：保留序列形状与实体域，候选维度由执行环境逐行处理。
        return OperatorSupport.fixedInference(inputs, DataType.STRING,
                shape == ValueShape.SEQUENCE ? ValueShape.SEQUENCE : ValueShape.SCALAR);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        Object value = arguments.get(0);
        if (OperatorSupport.isSequence(value)) {
            return OperatorSupport.mapSequence(value, name(), LowerOperator::lower);
        }
        return lower(value);
    }

    private static String lower(Object value) {
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(
                    "lower requires STRING value, got: " + OperatorSupport.typeName(value));
        }
        return ((String) value).toLowerCase(Locale.ROOT);
    }
}
