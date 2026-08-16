package com.example.featuredag.operator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 默认 Batch 适配器：保持逐行调用次数、顺序和异常语义。 */
public final class SingleLoopBatchOperatorKernel implements BatchOperatorKernel {
    private final SingleOperatorKernel singleKernel;

    public SingleLoopBatchOperatorKernel(SingleOperatorKernel singleKernel) {
        this.singleKernel = Objects.requireNonNull(singleKernel, "singleKernel");
    }

    @Override
    public BatchOperatorResult evaluateBatch(BatchOperatorCall call) {
        List<Object> result = new ArrayList<>(call.rowCount());
        for (int rowIndex = 0; rowIndex < call.rowCount(); rowIndex++) {
            try {
                List<Object> arguments = new ArrayList<>(call.arguments().size());
                for (BatchColumn argument : call.arguments()) {
                    arguments.add(argument.valueAt(rowIndex));
                }
                result.add(singleKernel.evaluate(arguments));
            } catch (RuntimeException error) {
                throw new BatchOperatorEvaluationException(rowIndex, error);
            }
        }
        return new BatchOperatorResult(ListBatchColumn.owned(result));
    }

    @Override
    public BatchKernelKind batchKernelKind() {
        return BatchKernelKind.SCALAR_ADAPTER;
    }
}
