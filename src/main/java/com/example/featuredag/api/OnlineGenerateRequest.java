package com.example.featuredag.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class OnlineGenerateRequest implements GenerateRequest {
    private final String executionId;
    private final Map<String, List<?>> sharedValues;
    private final List<Map<String, List<?>>> candidates;

    public OnlineGenerateRequest(
            String executionId,
            Map<String, List<?>> sharedValues,
            List<Map<String, List<?>>> candidates) {
        this.executionId = requireText(executionId, "executionId");
        this.sharedValues = FeatureValueCollections.immutableFeatureMap(sharedValues);
        this.candidates = FeatureValueCollections.immutableCandidates(candidates);
    }

    @Override
    public String executionId() { return executionId; }
    public Map<String, List<?>> sharedValues() { return sharedValues; }
    public List<Map<String, List<?>>> candidates() { return candidates; }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
