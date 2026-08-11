package com.example.featuredag.operator;

/** Batch 算子执行契约；逐行结果必须与 SingleOperatorKernel 等价。 */
@FunctionalInterface
public interface BatchOperatorKernel {
    BatchOperatorResult evaluateBatch(BatchOperatorCall call);

    default BatchKernelKind batchKernelKind() {
        return BatchKernelKind.NATIVE;
    }
}
