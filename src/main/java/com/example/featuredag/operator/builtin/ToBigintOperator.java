package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;

import java.util.List;

/**
 * to_bigint：把数值标量转换为 64 位 bigint 载体（java.lang.Long）。
 *
 * <p>本算子固定推断为独立的 DataType.BIGINT 并产出 Long，
 * 与 {@link ToIntOperator} 只差类型及载体宽度：小数部分向零截断（与 SQL CAST 语义一致），
 * 超出 long 范围直接失败而非回绕；字符串不做隐式数值转换，非数值输入在求值期拒绝。
 *
 * <p>不提供原生 BatchOperatorKernel：每行只做一次十进制截断，批内没有可复用的
 * 中间量，key 分配与查找开销反噬（成本模型见 AGENTS.md），
 * 由 SingleLoopBatchOperatorKernel 逐行适配，结果与 Single 完全一致。
 */
public final class ToBigintOperator extends AbstractBuiltinOperator {
    public ToBigintOperator() {
        super("to_bigint", 1, 1, true, false);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        OperatorSupport.rejectEventSequence(name(), inputs);
        return OperatorSupport.fixedInference(inputs, DataType.BIGINT, ValueShape.SCALAR);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        return OperatorSupport.truncatedLong(arguments.get(0), name());
    }
}
