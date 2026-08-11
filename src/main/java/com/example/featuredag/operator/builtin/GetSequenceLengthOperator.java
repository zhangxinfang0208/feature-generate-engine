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
import java.util.List;

public final class GetSequenceLengthOperator extends AbstractBuiltinOperator
        implements BatchOperatorKernel {
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

    @Override
    public BatchOperatorResult evaluateBatch(BatchOperatorCall call) {
        List<Object> result = new ArrayList<Object>(call.rowCount());
        for (int rowIndex = 0; rowIndex < call.rowCount(); rowIndex++) {
            try {
                result.add(evaluateSequence(call.arguments().get(0).valueAt(rowIndex)));
            } catch (RuntimeException error) {
                throw OperatorSupport.batchFailure(rowIndex, error);
            }
        }
        return new BatchOperatorResult(new ListBatchColumn(result));
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
