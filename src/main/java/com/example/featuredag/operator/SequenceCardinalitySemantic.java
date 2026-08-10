package com.example.featuredag.operator;

/** 表示算子返回某个序列输入的元素数量。 */
public record SequenceCardinalitySemantic(
        int sequenceInputIndex) implements OperatorSemantic {

    public SequenceCardinalitySemantic {
        if (sequenceInputIndex < 0) {
            throw new IllegalArgumentException("sequenceInputIndex must be non-negative");
        }
    }
}
