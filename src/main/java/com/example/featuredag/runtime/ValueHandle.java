package com.example.featuredag.runtime;

import com.example.featuredag.definition.ValueShape;

/**
 * 运行态值句柄：sealed 分支与逻辑层 ValueShape 对应。
 * OfflineBatchValue、RequestBatchValue、CandidateBatchValue 是批执行外层容器，
 * 其 shape() 返回单个元素的逻辑形状；
 * raw() 暴露底层值供算子求值。
 */
public sealed interface ValueHandle
        permits ScalarValue, CandidateVectorValue, OfflineBatchValue,
                RequestBatchValue, CandidateBatchValue,
                SequenceValue, IndexValue, ListSequenceValue {
    ValueShape shape();
    Object raw();
}
