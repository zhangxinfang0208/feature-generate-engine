package com.example.featuredag.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行业索引构建器（运行时）：把序列按 industry 分组为 baseIndex 数组，
 * 供 countIndustry 融合算子 O(1) 计数；索引按序列句柄缓存（REQUEST_INDEX 缓存）。
 */
public final class SequenceIndustryIndex {
    private SequenceIndustryIndex() {}

    public static IndexValue build(SequenceValue sequence) {
        SequenceBlock base = sequence.baseBlock();
        Map<String, List<Integer>> temp = new LinkedHashMap<>();
        for (int logicalIndex = 0; logicalIndex < sequence.size(); logicalIndex++) {
            int baseIndex = sequence.baseIndexAt(logicalIndex);
            temp.computeIfAbsent(
                    base.industryAtBaseIndex(baseIndex),
                    ignored -> new ArrayList<>()).add(baseIndex);
        }
        Map<String, int[]> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> entry : temp.entrySet()) {
            result.put(entry.getKey(), entry.getValue().stream().mapToInt(Integer::intValue).toArray());
        }
        return new IndexValue(result);
    }
}
