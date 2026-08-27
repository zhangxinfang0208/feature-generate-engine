package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.operator.OperatorInference;

import java.util.List;

/**
 * to_int：把数值或十进制数字字符串标量/序列转换为 32 位 int 载体（java.lang.Integer）。
 *
 * <p>本算子固定推断元素类型为 DataType.INT 并产出 Integer；输入为序列时
 * 逐元素转换并保持长度与顺序。小数部分向零截断
 * （与 SQL CAST 语义一致），超出 int 范围直接失败而非回绕；
 * 字符串只在本显式转换入口解析；空串、非数字字符串和其他非数值输入在求值期拒绝。
 *
 * <p>不提供原生 BatchOperatorKernel：每个输入元素只做一次十进制截断，批内没有可复用的
 * 中间量，key 分配与查找开销反噬（成本模型见 AGENTS.md），
 * 由 SingleLoopBatchOperatorKernel 逐行适配，结果与 Single 完全一致。
 */
public final class ToIntOperator extends AbstractBuiltinOperator {
    public ToIntOperator() {
        super("to_int", 1, 1, true, true);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.numericCastInference(name(), inputs, DataType.INT);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        Object value = arguments.get(0);
        if (OperatorSupport.isSequence(value)) {
            return OperatorSupport.mapSequence(
                    value,
                    name(),
                    element -> Integer.valueOf(
                            OperatorSupport.truncatedInt(element, name())));
        }
        return Integer.valueOf(OperatorSupport.truncatedInt(value, name()));
    }
}
