package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.operator.OperatorInference;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * mul：数值标量乘法。
 *
 * <p>精确十进制求积后按输入载体定宽：双方均为整型载体时产出 Long（溢出直接失败
 * 不回绕，与 to_bigint 的失败语义一致），任一浮点载体产出 Double。
 * 类型推断按 DOUBLE &gt; BIGINT &gt; INT 取宽度上界。
 *
 * <p>不提供原生 BatchOperatorKernel：每行只做一次轻量十进制乘法，批内没有可复用的
 * 中间量，key 分配与查找开销反噬（成本模型见 AGENTS.md），
 * 由 SingleLoopBatchOperatorKernel 逐行适配，结果与 Single 完全一致。
 */
public final class MulOperator extends AbstractBuiltinOperator {
    private static final List<String> PARAMETER_NAMES = Collections.unmodifiableList(
            Arrays.asList("value", "multiplier"));

    public MulOperator() {
        super("mul", 2, 2, true, false);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        OperatorSupport.rejectEventSequence(name(), inputs);
        return OperatorSupport.fixedInference(
                inputs, OperatorSupport.numericResultType(inputs), ValueShape.SCALAR);
    }

    @Override
    public List<String> parameterNames() {
        return PARAMETER_NAMES;
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        Object left = arguments.get(0);
        Object right = arguments.get(1);
        BigDecimal product = OperatorSupport.arithmeticOperand(left, name())
                .multiply(OperatorSupport.arithmeticOperand(right, name()));
        return OperatorSupport.arithmeticCarrier(product, left, right, name());
    }
}
