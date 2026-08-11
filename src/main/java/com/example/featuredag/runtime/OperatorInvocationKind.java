package com.example.featuredag.runtime;

/** 物理算子节点在一次执行中实际选择的调用路径。 */
public enum OperatorInvocationKind {
    SINGLE,
    BATCH_NATIVE,
    BATCH_SCALAR_ADAPTER,
    SPECIALIZED;

    public boolean isBatch() {
        return this == BATCH_NATIVE || this == BATCH_SCALAR_ADAPTER;
    }
}
