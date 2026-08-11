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
    public FindIndicesOperator() {
        super("find_indices", 2, 2, true, false, true);
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
        List<Object> result = new ArrayList<Object>(call.rowCount());
        Map<OperatorSupport.IdentityBatchKey, Map<Object, List<Integer>>> indexes =
                new LinkedHashMap<OperatorSupport.IdentityBatchKey, Map<Object, List<Integer>>>();
        for (int rowIndex = 0; rowIndex < call.rowCount(); rowIndex++) {
            try {
                Object sequence = call.arguments().get(0).valueAt(rowIndex);
                OperatorSupport.IdentityBatchKey key = OperatorSupport.identityBatchKey(
                        call.layout().groupIndexAt(rowIndex), sequence);
                Map<Object, List<Integer>> index = indexes.get(key);
                if (index == null) {
                    index = buildIndex(sequence);
                    indexes.put(key, index);
                }
                Object target = call.arguments().get(1).valueAt(rowIndex);
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
