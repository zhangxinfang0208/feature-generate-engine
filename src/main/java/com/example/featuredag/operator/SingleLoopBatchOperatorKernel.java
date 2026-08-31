package com.example.featuredag.operator;

import java.util.List;
import java.util.Objects;

/** 默认 Batch 适配器：保持逐行调用次数、顺序和异常语义。 */
public final class SingleLoopBatchOperatorKernel implements RecoverableBatchOperatorKernel {
    private final SingleOperatorKernel singleKernel;

    public SingleLoopBatchOperatorKernel(SingleOperatorKernel singleKernel) {
        this.singleKernel = Objects.requireNonNull(singleKernel, "singleKernel");
    }

    @Override
    public BatchOperatorResult evaluateBatch(BatchOperatorCall call) {
        BatchOperatorResultBuilder result = new BatchOperatorResultBuilder(call.rowCount());
        for (int rowIndex = 0; rowIndex < call.rowCount(); rowIndex++) {
            List<Object> arguments = new java.util.ArrayList<Object>(call.arguments().size());
            for (BatchColumn argument : call.arguments()) {
                arguments.add(argument.valueAt(rowIndex));
            }
            try {
                result.addValue(singleKernel.evaluate(arguments));
            } catch (RuntimeException error) {
                result.addFailure(error);
            }
        }
        return result.build();
    }

    @Override
    public BatchKernelKind batchKernelKind() {
        return BatchKernelKind.SCALAR_ADAPTER;
    }
}
