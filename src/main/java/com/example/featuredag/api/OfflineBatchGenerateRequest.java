package com.example.featuredag.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 离线批请求：每个元素是一行 RAW 特征，行内值继续遵循公共 API 的 List 契约。
 */
public final class OfflineBatchGenerateRequest {
    private final String executionId;
    private final List<Map<String, List<?>>> rows;

    public OfflineBatchGenerateRequest(
            String executionId,
            List<? extends Map<String, ? extends List<?>>> rows) {
        this.executionId = requireText(executionId, "executionId");
        this.rows = FeatureValueCollections.immutableFeatureRows(rows);
    }

    public String executionId() { return executionId; }
    public List<Map<String, List<?>>> rows() { return rows; }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
