package com.example.featuredag.operator;

/** 标记 Batch 内核能够把每行 RuntimeException 表达为 {@link BatchOperatorResult}。 */
public interface RecoverableBatchOperatorKernel extends BatchOperatorKernel {
}
