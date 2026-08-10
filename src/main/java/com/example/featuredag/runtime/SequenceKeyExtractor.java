package com.example.featuredag.runtime;

/** 从序列底层列式块提取某个 baseIndex 对应的分组 key。 */
@FunctionalInterface
public interface SequenceKeyExtractor {
    Object extract(SequenceBlock block, int baseIndex);
}
