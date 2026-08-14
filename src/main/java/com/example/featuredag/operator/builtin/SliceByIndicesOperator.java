package com.example.featuredag.operator.builtin;

import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.operator.OperatorInference;

import java.util.ArrayList;
import java.util.List;

/**
 * slice_by_indices：按下标切片。
 *
 * <p>不提供原生 BatchOperatorKernel：批内按 (group, sequence, indices) 键复用整个切片，
 * 但每行只省 O(下标数) 次取值，复用收益不足以覆盖批开销（实测 batch 劣化约 0.3x），
 * 由 SingleLoopBatchOperatorKernel 逐行适配，结果与 Single 完全一致。
 */
public final class SliceByIndicesOperator extends AbstractBuiltinOperator {
    public SliceByIndicesOperator() {
        super("slice_by_indices", 2, 2, true, true);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        // 输出元素类型、实体域和序列形状继承第一个输入，indices 只决定选择范围。
        return OperatorSupport.passThroughInference(inputs, 0);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        return slice(arguments.get(0), arguments.get(1));
    }

    private List<Object> slice(Object rawSequence, Object rawIndices) {
        int sequenceSize = OperatorSupport.sequenceSize(rawSequence, name(), "sequence");
        int indexCount = OperatorSupport.sequenceSize(rawIndices, name(), "indices");
        List<Object> result = new ArrayList<Object>(indexCount);
        // 每个下标先做整数和边界校验；允许重复下标，并严格保持 indices 的顺序。
        for (int position = 0; position < indexCount; position++) {
            int index = OperatorSupport.asSequenceIndex(
                    OperatorSupport.sequenceElementAt(
                            rawIndices, position, name(), "indices"),
                    position,
                    sequenceSize,
                    name());
            result.add(OperatorSupport.sequenceElementAt(
                    rawSequence, index, name(), "sequence"));
        }
        return OperatorSupport.immutableList(result);
    }
}
