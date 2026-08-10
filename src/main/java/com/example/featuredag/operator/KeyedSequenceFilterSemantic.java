package com.example.featuredag.operator;

import java.util.Objects;

/** 表示算子按一个候选 key 过滤序列，可参与“过滤后聚合”等物理改写。 */
public record KeyedSequenceFilterSemantic(
        int sequenceInputIndex,
        int keyInputIndex,
        SequenceKeyDomain keyDomain) implements OperatorSemantic {

    public KeyedSequenceFilterSemantic {
        if (sequenceInputIndex < 0) {
            throw new IllegalArgumentException("sequenceInputIndex must be non-negative");
        }
        if (keyInputIndex < 0) {
            throw new IllegalArgumentException("keyInputIndex must be non-negative");
        }
        if (sequenceInputIndex == keyInputIndex) {
            throw new IllegalArgumentException("sequence and key inputs must be different");
        }
        Objects.requireNonNull(keyDomain, "keyDomain");
    }
}
