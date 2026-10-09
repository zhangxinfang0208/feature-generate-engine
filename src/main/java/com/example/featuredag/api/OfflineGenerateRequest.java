package com.example.featuredag.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class OfflineGenerateRequest implements GenerateRequest {
    private final String executionId;
    private final Map<String, List<?>> rowValues;

    public OfflineGenerateRequest(String executionId, Map<String, List<?>> rowValues) {
        this(executionId, rowValues, false);
    }

    @Override
    public String executionId() { return executionId; }
    public Map<String, List<?>> rowValues() { return rowValues; }

    /**
     * 同步离线单行执行只读借用入口，不复制 Map 或序列。
     * 构造后至最后一次 generate 返回前，调用方不得修改输入及嵌套值或并发写入。
     * 输出为独立不可变结果；需要输入快照时使用普通构造器。
     */
    public static OfflineGenerateRequest borrowed(String executionId, Map<String, List<?>> rowValues) {
        return new OfflineGenerateRequest(executionId, rowValues, true);
    }

    private OfflineGenerateRequest(String executionId, Map<String, List<?>> rowValues, boolean borrowed) {
        this.executionId = requireText(executionId, "executionId");
        this.rowValues = borrowed
                ? FeatureValueCollections.borrowedFeatureMap(rowValues)
                : FeatureValueCollections.immutableFeatureMap(rowValues);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
