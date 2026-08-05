package com.example.featuredag.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class GenerateResult {
    private final String executionId;
    private final Map<String, Object> featureValues;
    private final List<Map<String, Object>> candidateFeatureValues;

    public GenerateResult(
            String executionId,
            Map<String, Object> featureValues,
            List<Map<String, Object>> candidateFeatureValues) {
        this.executionId = Objects.requireNonNull(executionId, "executionId");
        this.featureValues = immutableMap(featureValues);
        Objects.requireNonNull(candidateFeatureValues, "candidateFeatureValues");
        this.candidateFeatureValues = candidateFeatureValues.stream()
                .map(GenerateResult::immutableMap)
                .toList();
    }

    public String executionId() { return executionId; }
    public Map<String, Object> featureValues() { return featureValues; }
    public List<Map<String, Object>> candidateFeatureValues() { return candidateFeatureValues; }

    private static Map<String, Object> immutableMap(Map<String, Object> values) {
        Objects.requireNonNull(values, "values");
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
