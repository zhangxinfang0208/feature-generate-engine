package com.example.featuredag.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 通用序列等值索引：按 SequenceValue 的逻辑选择范围把 key 映射为 baseIndex 数组。 */
public final class SequenceKeyIndex {
    private SequenceKeyIndex() {}

    public static IndexValue build(SequenceValue sequence, SequenceKeyExtractor extractor) {
        Map<Object, List<Integer>> positions = new LinkedHashMap<>();
        for (int logicalIndex = 0; logicalIndex < sequence.size(); logicalIndex++) {
            int baseIndex = sequence.baseIndexAt(logicalIndex);
            Object key = extractor.extract(sequence.baseBlock(), baseIndex);
            positions.computeIfAbsent(key, ignored -> new ArrayList<>()).add(baseIndex);
        }
        Map<Object, int[]> result = new LinkedHashMap<>();
        for (Map.Entry<Object, List<Integer>> entry : positions.entrySet()) {
            result.put(
                    entry.getKey(),
                    entry.getValue().stream().mapToInt(Integer::intValue).toArray());
        }
        return new IndexValue(result);
    }
}
