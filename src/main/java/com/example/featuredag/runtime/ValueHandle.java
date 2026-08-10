package com.example.featuredag.runtime;

import com.example.featuredag.logical.ValueShape;

/**
 * 运行态值句柄：sealed 分支与逻辑层 ValueShape 一一对应——
 * SCALAR→ScalarValue、CANDIDATE_VECTOR→CandidateVectorValue、
 * SEQUENCE→SequenceValue/ListSequenceValue、INDEX→IndexValue；
 * raw() 暴露底层值供算子求值。
 */
public sealed interface ValueHandle
        permits ScalarValue, CandidateVectorValue, SequenceValue, IndexValue, ListSequenceValue {
    ValueShape shape();
    Object raw();
}
