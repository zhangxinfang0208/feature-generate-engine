package com.example.featuredag.planning;

import java.util.List;
import java.util.Objects;

public record CountExtractIndustryMatch(
        String countNodeId,
        String extractNodeId,
        List<String> intermediateNodeIds) {
    public CountExtractIndustryMatch {
        Objects.requireNonNull(countNodeId, "countNodeId");
        Objects.requireNonNull(extractNodeId, "extractNodeId");
        intermediateNodeIds = List.copyOf(intermediateNodeIds);
    }
}
