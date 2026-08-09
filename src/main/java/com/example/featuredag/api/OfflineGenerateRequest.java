package com.example.featuredag.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class OfflineGenerateRequest implements GenerateRequest {
    private final String executionId;
    private final Map<String, List<?>> rowValues;

    public OfflineGenerateRequest(String executionId, Map<String, List<?>> rowValues) {
        this.executionId = requireText(executionId, "executionId");
        this.rowValues = FeatureValueCollections.immutableFeatureMap(rowValues);
    }

    @Override
    public String executionId() { return executionId; }
    public Map<String, List<?>> rowValues() { return rowValues; }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
