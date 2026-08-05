package com.example.featuredag.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SequenceIndustryIndex {
    private SequenceIndustryIndex() {}

    public static IndexValue build(SequenceBlock sequence) {
        Map<String, List<Integer>> temp = new LinkedHashMap<>();
        for (int i = 0; i < sequence.size(); i++) {
            temp.computeIfAbsent(sequence.industryAtBaseIndex(i), ignored -> new ArrayList<>()).add(i);
        }
        Map<String, int[]> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> entry : temp.entrySet()) {
            result.put(entry.getKey(), entry.getValue().stream().mapToInt(Integer::intValue).toArray());
        }
        return new IndexValue(result);
    }
}
