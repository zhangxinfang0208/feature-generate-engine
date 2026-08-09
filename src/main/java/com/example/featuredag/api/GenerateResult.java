package com.example.featuredag.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class GenerateResult {
    private final String executionId;
    private final Map<String, List<?>> featureValues;
    private final List<Map<String, List<?>>> candidateFeatureValues;

    public GenerateResult(
            String executionId,
            Map<String, List<?>> featureValues,
            List<Map<String, List<?>>> candidateFeatureValues) {
        this.executionId = Objects.requireNonNull(executionId, "executionId");
        this.featureValues = FeatureValueCollections.immutableFeatureMap(featureValues);
        this.candidateFeatureValues = FeatureValueCollections.immutableCandidates(
                candidateFeatureValues);
    }

    public String executionId() { return executionId; }
    public Map<String, List<?>> featureValues() { return featureValues; }
    public List<Map<String, List<?>>> candidateFeatureValues() { return candidateFeatureValues; }
}
