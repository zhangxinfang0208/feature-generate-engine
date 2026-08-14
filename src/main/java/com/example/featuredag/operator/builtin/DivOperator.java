package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.operator.OperatorInference;

import java.math.BigDecimal;
import java.util.List;

/**
 * div：数值标量除法，固定推断并产出 DOUBLE。
 *
 * <p>整型相除不做截断商（1/2 截断为 0 会吞掉比率信息），与 Hive/Spark 的
 * 整除提升 double 语义一致，因此宽度上界规则（numericResultType）不适用于除法。
 *
 * <p>防除 0：分母为 0（含 -0.0）时返回 0.0——比率语义下「无分母流量」即比率为 0，
 * 调用方可省去表达式内手工 +epsilon 的防除零写法。商按 DECIMAL64（16 位有效数字）
 * 与 double 精度对齐，超出 double 表示范围视为溢出。
 *
 * <p>不提供原生 BatchOperatorKernel：每行只做一次十进制除法，批内没有可复用的
 * 中间量，key 分配与查找开销反噬（成本模型见 AGENTS.md），
 * 由 SingleLoopBatchOperatorKernel 逐行适配，结果与 Single 完全一致。
 */
public final class DivOperator extends AbstractBuiltinOperator {
    public DivOperator() {
        super("div", 2, 2, true, false);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        OperatorSupport.rejectEventSequence(name(), inputs);
        return OperatorSupport.fixedInference(inputs, DataType.DOUBLE, ValueShape.SCALAR);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        BigDecimal dividend = OperatorSupport.arithmeticOperand(arguments.get(0), name());
        BigDecimal divisor = OperatorSupport.arithmeticOperand(arguments.get(1), name());
        if (divisor.signum() == 0) {
            // 防除 0：分母为 0（含 -0.0）按比率语义返回 0.0，而非抛除零异常。
            return Double.valueOf(0.0);
        }
        return Double.valueOf(OperatorSupport.finiteQuotient(dividend, divisor, name()));
    }
}
