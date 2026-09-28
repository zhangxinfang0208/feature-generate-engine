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
        if (singleKernel instanceof BorrowedArgumentsKernel) {
            return evaluateBorrowedArguments(call);
        }
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

    private BatchOperatorResult evaluateBorrowedArguments(BatchOperatorCall call) {
        BatchOperatorResultBuilder result = new BatchOperatorResultBuilder(call.rowCount());
        // 每次 Batch 调用独占容器；不能放在共享 Kernel 实例或 ThreadLocal 中。
        Object[] values = new Object[call.arguments().size()];
        List<Object> arguments = java.util.Collections.unmodifiableList(
                java.util.Arrays.asList(values));
        for (int rowIndex = 0; rowIndex < call.rowCount(); rowIndex++) {
            // 与普通路径一样，按参数顺序读取一次，列读取错误不转为行级错误。
            for (int columnIndex = 0; columnIndex < values.length; columnIndex++) {
                values[columnIndex] = call.arguments().get(columnIndex).valueAt(rowIndex);
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
