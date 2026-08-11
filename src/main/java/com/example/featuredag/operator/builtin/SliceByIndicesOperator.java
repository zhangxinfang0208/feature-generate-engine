package com.example.featuredag.operator.builtin;

import com.example.featuredag.operator.BatchOperatorCall;
import com.example.featuredag.operator.BatchOperatorKernel;
import com.example.featuredag.operator.BatchOperatorResult;
import com.example.featuredag.operator.ListBatchColumn;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.operator.OperatorInference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SliceByIndicesOperator extends AbstractBuiltinOperator
        implements BatchOperatorKernel {
    public SliceByIndicesOperator() {
        super("slice_by_indices", 2, 2, true, true, true);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.passThroughInference(inputs, 0);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        return slice(arguments.get(0), arguments.get(1));
    }

    @Override
    public BatchOperatorResult evaluateBatch(BatchOperatorCall call) {
        List<Object> result = new ArrayList<Object>(call.rowCount());
        Map<OperatorSupport.IdentityBatchKey, Object> slices =
                new LinkedHashMap<OperatorSupport.IdentityBatchKey, Object>();
        for (int rowIndex = 0; rowIndex < call.rowCount(); rowIndex++) {
            try {
                Object sequence = call.arguments().get(0).valueAt(rowIndex);
                Object indices = call.arguments().get(1).valueAt(rowIndex);
                OperatorSupport.IdentityBatchKey key = OperatorSupport.identityBatchKey(
                        call.layout().groupIndexAt(rowIndex), sequence, indices);
                Object value = slices.get(key);
                if (value == null) {
                    value = slice(sequence, indices);
                    slices.put(key, value);
                }
                result.add(value);
            } catch (RuntimeException error) {
                throw OperatorSupport.batchFailure(rowIndex, error);
            }
        }
        return new BatchOperatorResult(new ListBatchColumn(result));
    }

    private List<Object> slice(Object rawSequence, Object rawIndices) {
        List<?> sequence = OperatorSupport.asList(rawSequence, name(), "sequence");
        List<?> indices = OperatorSupport.asList(rawIndices, name(), "indices");
        List<Object> result = new ArrayList<Object>(indices.size());
        for (int position = 0; position < indices.size(); position++) {
            int index = OperatorSupport.asSequenceIndex(
                    indices.get(position), position, sequence.size(), name());
            result.add(sequence.get(index));
        }
        return OperatorSupport.immutableList(result);
    }
}
