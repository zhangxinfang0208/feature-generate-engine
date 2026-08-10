package com.example.featuredag.runtime;

import com.example.featuredag.logical.ValueShape;

/**
 * 运行态值句柄：sealed 分支与逻辑层 ValueShape 对应。
 * OfflineBatchValue 是离线批执行的外层容器，其 shape() 返回单行元素形状；
 * raw() 暴露底层值供算子求值。
 */
public sealed interface ValueHandle
        permits ScalarValue, CandidateVectorValue, OfflineBatchValue,
                SequenceValue, IndexValue, ListSequenceValue {
    ValueShape shape();
    Object raw();
}
