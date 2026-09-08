package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.BatchOperatorCall;
import com.example.featuredag.operator.BatchOperatorResult;
import com.example.featuredag.operator.BatchOperatorResultBuilder;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.operator.RecoverableBatchOperatorKernel;
import com.example.featuredag.operator.SingleLoopBatchOperatorKernel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 返回源序列中命中任一目标值的全部逻辑下标，并保持源序列顺序。 */
public final class FindIndicesAnyOperator extends AbstractBuiltinOperator
        implements RecoverableBatchOperatorKernel {
    /** 低于该长度时实测线性扫描优于索引构建与位置合并。 */
    private static final int MIN_INDEXED_SOURCE_SIZE = 512;
    private static final int MIN_SHARED_ROW_COUNT = 4;

    public FindIndicesAnyOperator() {
        super("find_indices_any", 2, 2, true, true);
    }

    @Override
    public List<String> parameterNames() {
        return Arrays.asList("sequence", "targets");
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        for (int index = 0; index < inputs.size(); index++) {
            if (inputs.get(index).valueShape() != ValueShape.SEQUENCE) {
                throw new IllegalArgumentException(
                        "find_indices_any expects sequence input at position " + index);
            }
        }
        return OperatorSupport.fixedInference(inputs, DataType.INT, ValueShape.SEQUENCE);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        return find(arguments.get(0), arguments.get(1));
    }

    @Override
    public BatchOperatorResult evaluateBatch(BatchOperatorCall call) {
        if (!shouldUseIndexedBatch(call)) {
            return new SingleLoopBatchOperatorKernel(this).evaluateBatch(call);
        }
        BatchOperatorResultBuilder result = new BatchOperatorResultBuilder(call.rowCount());
        Map<Integer, IdentityHashMap<Object, Map<Object, List<Integer>>>> indexes =
                new LinkedHashMap<Integer,
                        IdentityHashMap<Object, Map<Object, List<Integer>>>>();
        for (int rowIndex = 0; rowIndex < call.rowCount(); rowIndex++) {
            Object source = call.arguments().get(0).valueAt(rowIndex);
            Object rawTargets = call.arguments().get(1).valueAt(rowIndex);
            int groupIndex = call.layout().groupIndexAt(rowIndex);
            try {
                int sourceSize = OperatorSupport.sequenceSize(source, name(), "sequence");
                if (sourceSize < MIN_INDEXED_SOURCE_SIZE) {
                    // 短序列沿用线性扫描，避免索引与合并开销反噬。
                    result.addValue(find(source, sourceSize, rawTargets));
                    continue;
                }

                IdentityHashMap<Object, Map<Object, List<Integer>>> groupIndexes =
                        indexes.get(Integer.valueOf(groupIndex));
                Map<Object, List<Integer>> index = groupIndexes == null
                        ? null
                        : groupIndexes.get(source);
                if (index == null && hasMinimumSharedRun(
                        call, rowIndex, groupIndex, source)) {
                    // 在线候选行按 group 连续排列；只有实际观察到复用才承担建索引成本。
                    index = buildIndex(source, sourceSize);
                    if (groupIndexes == null) {
                        groupIndexes = new IdentityHashMap<Object, Map<Object, List<Integer>>>();
                        indexes.put(Integer.valueOf(groupIndex), groupIndexes);
                    }
                    groupIndexes.put(source, index);
                }
                if (index == null) {
                    result.addValue(find(source, sourceSize, rawTargets));
                } else {
                    result.addValue(find(index, source, sourceSize, rawTargets));
                }
            } catch (RuntimeException error) {
                result.addFailure(error);
            }
        }
        return result.build();
    }

    private boolean shouldUseIndexedBatch(BatchOperatorCall call) {
        if (call.rowCount() < 2) return false;
        Object firstSource = call.arguments().get(0).valueAt(0);
        try {
            return OperatorSupport.sequenceSize(firstSource, name(), "sequence")
                            >= MIN_INDEXED_SOURCE_SIZE
                    && hasMinimumSharedRun(
                            call, 0, call.layout().groupIndexAt(0), firstSource);
        } catch (RuntimeException ignored) {
            // Adapter 负责保持首行失败和后续健康行继续执行的恢复语义。
            return false;
        }
    }

    private static boolean hasMinimumSharedRun(
            BatchOperatorCall call,
            int rowIndex,
            int groupIndex,
            Object source) {
        for (int offset = 1; offset < MIN_SHARED_ROW_COUNT; offset++) {
            int nextRowIndex = rowIndex + offset;
            if (nextRowIndex >= call.rowCount()
                    || call.layout().groupIndexAt(nextRowIndex) != groupIndex
                    || call.arguments().get(0).valueAt(nextRowIndex) != source) {
                return false;
            }
        }
        return true;
    }

    private List<Integer> find(Object source, Object rawTargets) {
        int sourceSize = OperatorSupport.sequenceSize(source, name(), "sequence");
        return find(source, sourceSize, rawTargets);
    }

    private List<Integer> find(
            Object source,
            int sourceSize,
            Object rawTargets) {
        int targetCount = OperatorSupport.sequenceSize(rawTargets, name(), "targets");
        Set<Object> targets = new LinkedHashSet<Object>();
        for (int index = 0; index < targetCount; index++) {
            targets.add(OperatorSupport.sequenceElementAt(
                    rawTargets, index, name(), "targets"));
        }

        return findByLinearScan(source, sourceSize, targets);
    }

    private List<Integer> findByLinearScan(
            Object source,
            int sourceSize,
            Set<Object> targets) {
        List<Integer> result = new ArrayList<Integer>();
        for (int index = 0; index < sourceSize; index++) {
            Object value = OperatorSupport.sequenceElementAt(
                    source, index, name(), "sequence");
            if (targets.contains(value)) result.add(Integer.valueOf(index));
        }
        return OperatorSupport.immutableList(result);
    }

    private List<Integer> find(
            Map<Object, List<Integer>> index,
            Object source,
            int sourceSize,
            Object rawTargets) {
        int targetCount = OperatorSupport.sequenceSize(rawTargets, name(), "targets");
        Set<Object> targets = new LinkedHashSet<Object>();
        for (int targetIndex = 0; targetIndex < targetCount; targetIndex++) {
            targets.add(OperatorSupport.sequenceElementAt(
                    rawTargets, targetIndex, name(), "targets"));
        }
        if (targets.isEmpty()) return Collections.emptyList();
        if ((long) targets.size() * 4L >= index.size()) {
            // 查询值已覆盖较多离散值时直接扫描，避免逐目标查表后仍因密集命中回退。
            return findByLinearScan(source, sourceSize, targets);
        }

        int matchedPositionCount = 0;
        for (Object target : targets) {
            List<Integer> positions = index.get(target);
            if (positions != null) matchedPositionCount += positions.size();
        }
        if ((long) matchedPositionCount * 4L >= sourceSize) {
            // 命中密集时，位置合并排序比直接扫描更贵。
            return findByLinearScan(source, sourceSize, targets);
        }

        List<Integer> result = new ArrayList<Integer>();
        for (Object target : targets) {
            List<Integer> positions = index.get(target);
            if (positions != null) result.addAll(positions);
        }
        // 各 value 的位置列表内部已有序；多目标合并后重新排序以恢复源序列顺序。
        Collections.sort(result);
        return OperatorSupport.immutableList(result);
    }

    private Map<Object, List<Integer>> buildIndex(Object source, int sourceSize) {
        Map<Object, List<Integer>> mutable = new LinkedHashMap<Object, List<Integer>>();
        for (int index = 0; index < sourceSize; index++) {
            Object value = OperatorSupport.sequenceElementAt(
                    source, index, name(), "sequence");
            List<Integer> positions = mutable.get(value);
            if (positions == null) {
                positions = new ArrayList<Integer>();
                mutable.put(value, positions);
            }
            positions.add(Integer.valueOf(index));
        }

        Map<Object, List<Integer>> result = new LinkedHashMap<Object, List<Integer>>();
        for (Map.Entry<Object, List<Integer>> entry : mutable.entrySet()) {
            result.put(entry.getKey(), OperatorSupport.immutableList(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }
}
