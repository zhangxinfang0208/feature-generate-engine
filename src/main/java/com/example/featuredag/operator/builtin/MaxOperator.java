package com.example.featuredag.operator.builtin;

import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.operator.OperatorInference;

import java.util.List;

/**
 * max：计算两个及以上数值标量或等长序列中的最大值，标量向序列广播。
 *
 * <p>比较采用精确十进制（BigDecimal），消除 Long 与 Double 混比时的精度歧义；
 * 相等时保留最左输入，并返回胜出参数的原数值载体。输出类型推断：
 * 按 DOUBLE &gt; BIGINT &gt; INT 取宽度上界。
 *
 * <p>不提供原生 BatchOperatorKernel：每行只做 O(n) 次轻量比较，批内没有可复用的
 * 中间量，key 分配与查找开销反噬，由 SingleLoopBatchOperatorKernel 逐行适配，
 * 结果与 Single 完全一致。
 */
public final class MaxOperator extends AbstractBuiltinOperator {
    public MaxOperator() {
        super("max", 2, Integer.MAX_VALUE, true, true);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.elementWiseNumericInference(
                name(), inputs, OperatorSupport.numericResultType(inputs));
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        return OperatorSupport.evaluateElementWise(
                arguments,
                name(),
                values -> OperatorSupport.selectExtreme(values, false, name()));
    }
}
