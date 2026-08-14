package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;

import java.util.List;

/**
 * to_int：把数值标量转换为 32 位 int 载体（java.lang.Integer）。
 *
 * <p>DataType.INT 同时覆盖 Integer 与 Long 载体，本算子固定产出 Integer：
 * 小数部分向零截断（与 SQL CAST 语义一致），超出 int 范围直接失败而非回绕；
 * 字符串不做隐式数值转换，非数值输入在求值期拒绝。
 *
 * <p>不提供原生 BatchOperatorKernel：每行只做一次十进制截断，批内没有可复用的
 * 中间量，key 分配与查找开销反噬（成本模型见 AGENTS.md），
 * 由 SingleLoopBatchOperatorKernel 逐行适配，结果与 Single 完全一致。
 */
public final class ToIntOperator extends AbstractBuiltinOperator {
    public ToIntOperator() {
        super("to_int", 1, 1, true, false);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        OperatorSupport.rejectEventSequence(name(), inputs);
        return OperatorSupport.fixedInference(inputs, DataType.INT, ValueShape.SCALAR);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        return OperatorSupport.truncatedInt(arguments.get(0), name());
    }
}
