package com.example.featuredag.operator.builtin;

import com.example.featuredag.operator.BatchColumn;
import com.example.featuredag.operator.BatchOperatorCall;
import com.example.featuredag.operator.BatchOperatorEvaluationException;
import com.example.featuredag.operator.BatchOperatorKernel;
import com.example.featuredag.operator.BatchOperatorResult;
import com.example.featuredag.operator.ListBatchColumn;
import com.example.featuredag.operator.OperatorSemantic;

import java.util.ArrayList;
import java.util.List;

/** Built-in operator with one native loop shared by single-row and batch execution. */
public abstract class AbstractRowWiseBuiltinOperator
        extends AbstractBuiltinOperator implements BatchOperatorKernel {
    protected AbstractRowWiseBuiltinOperator(
            String name,
            int minArguments,
            int maxArguments,
            boolean deterministic,
            boolean parameterized,
            boolean supportsSequenceView) {
        super(name, minArguments, maxArguments, deterministic, parameterized, supportsSequenceView);
    }

    protected AbstractRowWiseBuiltinOperator(
            String name,
            int minArguments,
            int maxArguments,
            boolean deterministic,
            boolean parameterized,
            boolean supportsSequenceView,
            long estimatedCost,
            List<OperatorSemantic> semantics) {
        super(name, minArguments, maxArguments, deterministic, parameterized,
                supportsSequenceView, estimatedCost, semantics);
    }

    @Override
    public final Object evaluate(List<Object> arguments) {
        return evaluateRow(new ListRowArguments(arguments));
    }

    @Override
    public final BatchOperatorResult evaluateBatch(BatchOperatorCall call) {
        List<Object> result = new ArrayList<>(call.rowCount());
        BatchRowArguments arguments = new BatchRowArguments(call);
        for (int rowIndex = 0; rowIndex < call.rowCount(); rowIndex++) {
            try {
                arguments.moveTo(rowIndex);
                result.add(evaluateRow(arguments));
            } catch (RuntimeException error) {
                throw new BatchOperatorEvaluationException(rowIndex, error);
            }
        }
        return new BatchOperatorResult(new ListBatchColumn(result));
    }

    protected abstract Object evaluateRow(RowArguments arguments);

    protected interface RowArguments {
        int size();
        Object get(int index);
        default Object getFirst() { return get(0); }
        default Object getLast() { return get(size() - 1); }
    }

    private record ListRowArguments(List<Object> values) implements RowArguments {
        @Override public int size() { return values.size(); }
        @Override public Object get(int index) { return values.get(index); }
    }

    private static final class BatchRowArguments implements RowArguments {
        private final BatchOperatorCall call;
        private int rowIndex;

        private BatchRowArguments(BatchOperatorCall call) {
            this.call = call;
        }

        private void moveTo(int value) {
            rowIndex = value;
        }

        @Override public int size() { return call.arguments().size(); }
        @Override public Object get(int index) {
            BatchColumn column = call.arguments().get(index);
            return column.valueAt(rowIndex);
        }
    }
}
