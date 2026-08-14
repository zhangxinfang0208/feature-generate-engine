package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.operator.OperatorInference;

import java.util.List;

/**
 * min：计算两个及以上数值标量中的最小值。
 *
 * <p>比较采用精确十进制（BigDecimal），消除 Long 与 Double 混比时的精度歧义；
 * 相等时保留最左输入，并返回胜出参数的原数值载体。输出类型推断：
 * 任一 DOUBLE 输入即提升为 DOUBLE，否则保持 INT。
 *
 * <p>不提供原生 BatchOperatorKernel：每行只做 O(n) 次轻量比较，批内没有可复用的
 * 中间量，key 分配与查找开销反噬，由 SingleLoopBatchOperatorKernel 逐行适配，
 * 结果与 Single 完全一致。
 */
public final class MinOperator extends AbstractBuiltinOperator {
    public MinOperator() {
        super("min", 2, Integer.MAX_VALUE, true, false);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        OperatorSupport.rejectEventSequence(name(), inputs);
        return OperatorSupport.fixedInference(
                inputs, OperatorSupport.numericResultType(inputs), ValueShape.SCALAR);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        return OperatorSupport.selectExtreme(arguments, true, name());
    }
}
