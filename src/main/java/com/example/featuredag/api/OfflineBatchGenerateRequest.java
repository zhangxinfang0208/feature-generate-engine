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

    /**
     * 同步离线批执行的只读借用入口：不复制行 Map 或特征序列。
     * 调用方必须从构造请求到最后一次 generateBatch 返回期间保持所有输入及嵌套值不变，
     * 包括其他线程和通过 rows() 取得的引用；禁止与输入写入并发。
     * 引擎不会修改输入；输出仍为独立不可变结果，可在返回后复用输入容器。
     * 需要保存输入快照时使用普通构造器。
     */
    public static OfflineBatchGenerateRequest borrowed(
            String executionId, List<? extends Map<String, ? extends List<?>>> rows) {
        return new OfflineBatchGenerateRequest(executionId, rows, true);
    }

    private OfflineBatchGenerateRequest(
            String executionId, List<? extends Map<String, ? extends List<?>>> rows, boolean borrowed) {
        this.executionId = requireText(executionId, "executionId");
        this.rows = FeatureValueCollections.borrowedFeatureRows(rows);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
