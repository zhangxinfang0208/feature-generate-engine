package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.BatchOperatorCall;
import com.example.featuredag.operator.BatchOperatorResult;
import com.example.featuredag.operator.BatchOperatorResultBuilder;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.RecoverableBatchOperatorKernel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 返回目标值在序列中的全部逻辑下标，未命中时返回不可变空列表。 */
public final class FindIndicesOperator extends AbstractBuiltinOperator
        implements RecoverableBatchOperatorKernel {
    public FindIndicesOperator() {
        super("find_indices", 2, 2, true, true);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.fixedInference(inputs, DataType.INT, ValueShape.SEQUENCE);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        return find(arguments.get(0), arguments.get(1));
    }

    @Override
    public BatchOperatorResult evaluateBatch(BatchOperatorCall call) {
        BatchOperatorResultBuilder result = new BatchOperatorResultBuilder(call.rowCount());
        // 候选批可能用多个 target 查询同一序列，先按序列身份构建 value→positions 索引再复用。
        Map<OperatorSupport.IdentityBatchKey, Map<Object, List<Integer>>> indexes =
                new LinkedHashMap<OperatorSupport.IdentityBatchKey, Map<Object, List<Integer>>>();
        for (int rowIndex = 0; rowIndex < call.rowCount(); rowIndex++) {
            Object sequence = call.arguments().get(0).valueAt(rowIndex);
            Object target = call.arguments().get(1).valueAt(rowIndex);
            int groupIndex = call.layout().groupIndexAt(rowIndex);
            try {
                OperatorSupport.IdentityBatchKey key = OperatorSupport.identityBatchKey(
                        groupIndex, sequence);
                Map<Object, List<Integer>> index = indexes.get(key);
                if (index == null) {
                    index = buildIndex(sequence);
                    indexes.put(key, index);
                }
                List<Integer> positions = index.get(target);
                result.addValue(positions == null
                        ? Collections.<Integer>emptyList()
                        : positions);
            } catch (RuntimeException error) {
                result.addFailure(error);
            }
        }
        return result.build();
    }

    private List<Integer> find(Object rawSequence, Object target) {
        int sequenceSize = OperatorSupport.sequenceSize(rawSequence, name(), "sequence");
        List<Integer> result = new ArrayList<Integer>();
        // Objects.equals 同时支持 null 元素和 null target，语义与索引路径保持一致。
        for (int index = 0; index < sequenceSize; index++) {
            if (Objects.equals(
                    OperatorSupport.sequenceElementAt(rawSequence, index, name(), "sequence"),
                    target)) {
                result.add(index);
            }
        }
        return OperatorSupport.immutableList(result);
    }

    private Map<Object, List<Integer>> buildIndex(Object rawSequence) {
        int sequenceSize = OperatorSupport.sequenceSize(rawSequence, name(), "sequence");
        Map<Object, List<Integer>> mutable = new LinkedHashMap<Object, List<Integer>>();
        for (int index = 0; index < sequenceSize; index++) {
            Object element = OperatorSupport.sequenceElementAt(
                    rawSequence, index, name(), "sequence");
            List<Integer> positions = mutable.get(element);
            if (positions == null) {
                positions = new ArrayList<Integer>();
                mutable.put(element, positions);
            }
            positions.add(index);
        }
        Map<Object, List<Integer>> result = new LinkedHashMap<Object, List<Integer>>();
        // 对外缓存前同时冻结位置列表和索引 Map，避免批内后续行意外修改共享结果。
        for (Map.Entry<Object, List<Integer>> entry : mutable.entrySet()) {
            result.put(entry.getKey(), OperatorSupport.immutableList(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }
}
