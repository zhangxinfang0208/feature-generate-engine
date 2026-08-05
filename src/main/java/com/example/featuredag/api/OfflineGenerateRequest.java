package com.example.featuredag.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class OfflineGenerateRequest implements GenerateRequest {
    private final String executionId;
    private final Map<String, Object> rowValues;

    public OfflineGenerateRequest(String executionId, Map<String, Object> rowValues) {
        this.executionId = requireText(executionId, "executionId");
        Objects.requireNonNull(rowValues, "rowValues");
        this.rowValues = Collections.unmodifiableMap(new LinkedHashMap<>(rowValues));
    }

    @Override
    public String executionId() { return executionId; }
    public Map<String, Object> rowValues() { return rowValues; }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
