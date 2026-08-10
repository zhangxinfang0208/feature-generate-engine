package com.example.featuredag.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 离线批执行结果；rows 与请求行严格按下标一一对应。 */
public final class OfflineBatchGenerateResult {
    private final String executionId;
    private final List<Map<String, List<?>>> rows;

    public OfflineBatchGenerateResult(
            String executionId,
            List<? extends Map<String, ? extends List<?>>> rows) {
        this.executionId = Objects.requireNonNull(executionId, "executionId");
        this.rows = FeatureValueCollections.immutableFeatureRows(rows);
    }

    public String executionId() { return executionId; }
    public List<Map<String, List<?>>> rows() { return rows; }
}
