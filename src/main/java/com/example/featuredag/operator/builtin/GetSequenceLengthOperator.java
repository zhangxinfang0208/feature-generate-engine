package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorSequence;

import java.util.Collection;
import java.util.List;

/**
 * get_seq_length：取序列长度。
 *
 * <p>不提供原生 BatchOperatorKernel：该算子单行计算极轻（一次 size 读取），
 * 原生 Batch 内核没有批内复用缓存，只会白付批开销（实测 batch 劣化约 0.2x），
 * 由 SingleLoopBatchOperatorKernel 逐行适配，结果与 Single 完全一致。
 */
public final class GetSequenceLengthOperator extends AbstractBuiltinOperator {
    public GetSequenceLengthOperator() {
        super("get_seq_length", 1, 1, true, false, false);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.fixedInference(inputs, DataType.INT, ValueShape.SCALAR);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        return evaluateSequence(arguments.get(0));
    }

    private static Object evaluateSequence(Object sequence) {
        if (sequence instanceof OperatorSequence) return ((OperatorSequence) sequence).size();
        if (sequence instanceof Collection<?>) return ((Collection<?>) sequence).size();
        if (sequence != null && sequence.getClass().isArray()) {
            return java.lang.reflect.Array.getLength(sequence);
        }
        throw new IllegalArgumentException(
                "get_seq_length expects a sequence, got: " + OperatorSupport.typeName(sequence));
    }
}
