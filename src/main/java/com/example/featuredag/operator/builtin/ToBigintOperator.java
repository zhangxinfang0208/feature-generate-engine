package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.operator.OperatorInference;

import java.util.List;

/**
 * to_bigint：把数值标量或数值序列转换为 64 位 bigint 载体（java.lang.Long）。
 *
 * <p>本算子固定推断元素类型为独立的 DataType.BIGINT 并产出 Long；
 * 输入为序列时逐元素转换并保持长度与顺序。
 * 与 {@link ToIntOperator} 只差类型及载体宽度：小数部分向零截断（与 SQL CAST 语义一致），
 * 超出 long 范围直接失败而非回绕；字符串不做隐式数值转换，非数值输入在推断期或求值期拒绝。
 *
 * <p>不提供原生 BatchOperatorKernel：每个输入元素只做一次十进制截断，批内没有可复用的
 * 中间量，key 分配与查找开销反噬（成本模型见 AGENTS.md），
 * 由 SingleLoopBatchOperatorKernel 逐行适配，结果与 Single 完全一致。
 */
public final class ToBigintOperator extends AbstractBuiltinOperator {
    public ToBigintOperator() {
        super("to_bigint", 1, 1, true, true);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.numericCastInference(name(), inputs, DataType.BIGINT);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        Object value = arguments.get(0);
        if (OperatorSupport.isSequence(value)) {
            return OperatorSupport.mapSequence(
                    value,
                    name(),
                    element -> Long.valueOf(
                            OperatorSupport.truncatedLong(element, name())));
        }
        return Long.valueOf(OperatorSupport.truncatedLong(value, name()));
    }
}
