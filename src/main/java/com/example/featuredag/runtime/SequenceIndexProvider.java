package com.example.featuredag.runtime;

import com.example.featuredag.operator.SequenceKeyDomain;

/** 一个序列 key 域对应的索引构建与查询 key 归一化策略。 */
public interface SequenceIndexProvider {
    SequenceKeyDomain keyDomain();
    SequenceKeyExtractor keyExtractor();

    default Object normalizeQueryKey(Object key) {
        return key;
    }

    default IndexValue build(SequenceValue sequence) {
        return SequenceKeyIndex.build(sequence, keyExtractor());
    }
}
