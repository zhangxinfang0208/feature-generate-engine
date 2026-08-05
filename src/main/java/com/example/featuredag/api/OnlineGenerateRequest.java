package com.example.featuredag.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class OnlineGenerateRequest implements GenerateRequest {
    private final String executionId;
    private final Map<String, Object> sharedValues;
    private final List<Map<String, Object>> candidates;

    public OnlineGenerateRequest(
            String executionId,
            Map<String, Object> sharedValues,
            List<Map<String, Object>> candidates) {
        this.executionId = requireText(executionId, "executionId");
        Objects.requireNonNull(sharedValues, "sharedValues");
        Objects.requireNonNull(candidates, "candidates");
        this.sharedValues = Collections.unmodifiableMap(new LinkedHashMap<>(sharedValues));
        this.candidates = candidates.stream()
                .map(candidate -> Collections.unmodifiableMap(
                        new LinkedHashMap<>(Objects.requireNonNull(candidate, "candidate"))))
                .toList();
    }

    @Override
    public String executionId() { return executionId; }
    public Map<String, Object> sharedValues() { return sharedValues; }
    public List<Map<String, Object>> candidates() { return candidates; }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
