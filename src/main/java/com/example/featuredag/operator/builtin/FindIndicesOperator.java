package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.BatchOperatorCall;
import com.example.featuredag.operator.BatchOperatorKernel;
import com.example.featuredag.operator.BatchOperatorResult;
import com.example.featuredag.operator.ListBatchColumn;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class FindIndicesOperator extends AbstractBuiltinOperator
        implements BatchOperatorKernel {
    /**
     * 相对成本单位由性能报告校准：建索引比线性扫描重，单次查表还包含 key/Map 开销。
     * 保留 20% 的收益余量，避免在临界点因为估算误差选择原生索引路径。
     */
    private static final long INDEX_BUILD_COST_PER_ELEMENT = 4L;
    private static final long INDEX_LOOKUP_COST_PER_ROW = 64L;
    private static final long INDEX_SETUP_COST = 4_096L;

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
        List<Object> sequences = new ArrayList<Object>(call.rowCount());
        List<OperatorSupport.IdentityBatchKey> rowKeys =
                new ArrayList<OperatorSupport.IdentityBatchKey>(call.rowCount());
        Map<OperatorSupport.IdentityBatchKey, Integer> occurrenceCounts =
                new LinkedHashMap<OperatorSupport.IdentityBatchKey, Integer>();

        // 先统计本批真实复用度；这里只选择 Native Kernel 内部算法，不改变物理计划路由（C10）。
        for (int rowIndex = 0; rowIndex < call.rowCount(); rowIndex++) {
            try {
                Object sequence = call.arguments().get(0).valueAt(rowIndex);
                OperatorSupport.IdentityBatchKey key = OperatorSupport.identityBatchKey(
                        call.layout().groupIndexAt(rowIndex), sequence);
                Integer occurrenceCount = occurrenceCounts.get(key);
                occurrenceCounts.put(key, occurrenceCount == null ? 1 : occurrenceCount + 1);
                sequences.add(sequence);
                rowKeys.add(key);
            } catch (RuntimeException error) {
                throw OperatorSupport.batchFailure(rowIndex, error);
            }
        }

        List<Object> result = new ArrayList<Object>(call.rowCount());
        Map<OperatorSupport.IdentityBatchKey, Map<Object, List<Integer>>> indexes =
                new LinkedHashMap<OperatorSupport.IdentityBatchKey, Map<Object, List<Integer>>>();
        for (int rowIndex = 0; rowIndex < call.rowCount(); rowIndex++) {
            try {
                Object sequence = sequences.get(rowIndex);
                OperatorSupport.IdentityBatchKey key = rowKeys.get(rowIndex);
                Object target = call.arguments().get(1).valueAt(rowIndex);
                int sequenceSize = OperatorSupport.asList(
                        sequence, name(), "sequence").size();

                if (!shouldBuildIndex(occurrenceCounts.get(key), sequenceSize)) {
                    result.add(find(sequence, target));
                    continue;
                }

                Map<Object, List<Integer>> index = indexes.get(key);
                if (index == null) {
                    index = buildIndex(sequence);
                    indexes.put(key, index);
                }
                List<Integer> positions = index.get(target);
                result.add(positions == null
                        ? Collections.<Integer>emptyList()
                        : positions);
            } catch (RuntimeException error) {
                throw OperatorSupport.batchFailure(rowIndex, error);
            }
        }
        return new BatchOperatorResult(new ListBatchColumn(result));
    }

    private static boolean shouldBuildIndex(int occurrenceCount, int sequenceSize) {
        if (occurrenceCount <= 1 || sequenceSize <= 0) return false;

        long scalarCost = saturatedMultiply(occurrenceCount, sequenceSize);
        long indexCost = saturatedAdd(
                saturatedAdd(
                        saturatedMultiply(INDEX_BUILD_COST_PER_ELEMENT, sequenceSize),
                        saturatedMultiply(INDEX_LOOKUP_COST_PER_ROW, occurrenceCount)),
                INDEX_SETUP_COST);

        // indexCost <= scalarCost * 80%：预计至少节省 20% 才承担索引分配成本。
        return saturatedMultiply(indexCost, 5L)
                <= saturatedMultiply(scalarCost, 4L);
    }

    private static long saturatedMultiply(long left, long right) {
        if (left == 0L || right == 0L) return 0L;
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
        return left * right;
    }

    private static long saturatedAdd(long left, long right) {
        if (left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private List<Integer> find(Object rawSequence, Object target) {
        List<?> sequence = OperatorSupport.asList(rawSequence, name(), "sequence");
        List<Integer> result = new ArrayList<Integer>();
        for (int index = 0; index < sequence.size(); index++) {
            if (Objects.equals(sequence.get(index), target)) result.add(index);
        }
        return OperatorSupport.immutableList(result);
    }

    private Map<Object, List<Integer>> buildIndex(Object rawSequence) {
        List<?> sequence = OperatorSupport.asList(rawSequence, name(), "sequence");
        Map<Object, List<Integer>> mutable = new LinkedHashMap<Object, List<Integer>>();
        for (int index = 0; index < sequence.size(); index++) {
            Object element = sequence.get(index);
            List<Integer> positions = mutable.get(element);
            if (positions == null) {
                positions = new ArrayList<Integer>();
                mutable.put(element, positions);
            }
            positions.add(index);
        }
        Map<Object, List<Integer>> result = new LinkedHashMap<Object, List<Integer>>();
        for (Map.Entry<Object, List<Integer>> entry : mutable.entrySet()) {
            result.put(entry.getKey(), OperatorSupport.immutableList(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }
}
