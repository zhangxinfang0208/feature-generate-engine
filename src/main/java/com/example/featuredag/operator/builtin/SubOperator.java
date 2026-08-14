package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.operator.OperatorInference;

import java.math.BigDecimal;
import java.util.List;

/**
 * sub：数值标量减法（左操作数减右操作数）。
 *
 * <p>精确十进制求差后按输入载体定宽：双方均为整型载体时产出 Long（溢出直接失败
 * 不回绕，与 to_bigint 的失败语义一致），任一浮点载体产出 Double。
 * 类型推断沿用宽度上界：任一 DOUBLE 输入提升为 DOUBLE，否则 INT。
 *
 * <p>不提供原生 BatchOperatorKernel：每行只做一次轻量十进制减法，批内没有可复用的
 * 中间量，key 分配与查找开销反噬（成本模型见 AGENTS.md），
 * 由 SingleLoopBatchOperatorKernel 逐行适配，结果与 Single 完全一致。
 */
public final class SubOperator extends AbstractBuiltinOperator {
    public SubOperator() {
        super("sub", 2, 2, true, false);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        OperatorSupport.rejectEventSequence(name(), inputs);
        return OperatorSupport.fixedInference(
                inputs, OperatorSupport.numericResultType(inputs), ValueShape.SCALAR);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        Object left = arguments.get(0);
        Object right = arguments.get(1);
        BigDecimal difference = OperatorSupport.arithmeticOperand(left, name())
                .subtract(OperatorSupport.arithmeticOperand(right, name()));
        return OperatorSupport.arithmeticCarrier(difference, left, right, name());
    }
}
