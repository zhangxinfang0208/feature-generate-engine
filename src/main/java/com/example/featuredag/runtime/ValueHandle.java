package com.example.featuredag.runtime;

import com.example.featuredag.logical.ValueShape;

public sealed interface ValueHandle
        permits ScalarValue, CandidateVectorValue, SequenceValue, IndexValue, ListSequenceValue {
    ValueShape shape();
    Object raw();
}
