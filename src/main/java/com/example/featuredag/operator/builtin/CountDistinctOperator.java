package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.BatchOperatorCall;
import com.example.featuredag.operator.BatchOperatorKernel;
import com.example.featuredag.operator.BatchOperatorResult;
import com.example.featuredag.operator.ListBatchColumn;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorSequence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class CountDistinctOperator extends AbstractBuiltinOperator
        implements BatchOperatorKernel {
    public CountDistinctOperator() {
        super("count_distinct", 1, 1, true, false, false);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.fixedInference(inputs, DataType.INT, ValueShape.SCALAR);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        return count(arguments.get(0));
    }

    @Override
    public BatchOperatorResult evaluateBatch(BatchOperatorCall call) {
        List<Object> result = new ArrayList<Object>(call.rowCount());
        Map<OperatorSupport.IdentityBatchKey, Integer> counts =
                new LinkedHashMap<OperatorSupport.IdentityBatchKey, Integer>();
        for (int rowIndex = 0; rowIndex < call.rowCount(); rowIndex++) {
            try {
                Object sequence = call.arguments().get(0).valueAt(rowIndex);
                OperatorSupport.IdentityBatchKey key = OperatorSupport.identityBatchKey(
                        call.layout().groupIndexAt(rowIndex), sequence);
                Integer count = counts.get(key);
                if (count == null) {
                    count = count(sequence);
                    counts.put(key, count);
                }
                result.add(count);
            } catch (RuntimeException error) {
                throw OperatorSupport.batchFailure(rowIndex, error);
            }
        }
        return new BatchOperatorResult(new ListBatchColumn(result));
    }

    private static int count(Object sequence) {
        LinkedHashSet<Object> values = new LinkedHashSet<Object>();
        if (sequence instanceof OperatorSequence) {
            OperatorSequence value = (OperatorSequence) sequence;
            for (int index = 0; index < value.size(); index++) {
                values.add(value.elementAt(index));
            }
        } else if (sequence instanceof Collection<?>) {
            values.addAll((Collection<?>) sequence);
        } else {
            throw new IllegalArgumentException(
                    "count_distinct expects a sequence, got: "
                            + OperatorSupport.typeName(sequence));
        }
        return values.size();
    }
}
