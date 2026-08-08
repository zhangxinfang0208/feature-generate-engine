package com.example.featuredag.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
