package com.example.featuredag.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单请求列式在线输入：candidateColumns 的第 i 个单元对应 candidateIds 的第 i 项。
 * 标量列形如 price=[99,50]，序列列形如 tags=[["sale","hot"],["new"]]。
 * null 单元表示该候选缺失此源特征；空序列仍是显式空序列。共享输入沿用 List 值契约。
 * 独立于 GenerateRequest，由 FeatureDagEngine.generateColumnar 调用，不改变旧接口。
 */
public final class OnlineColumnarGenerateRequest {
    private final String executionId;
    private final Map<String, List<?>> sharedValues;
    private final List<String> candidateIds;
    private final Map<String, List<?>> candidateColumns;

    /** 复制并冻结输入中的 Map/List 容器，包括候选单元内的序列和事件字段。 */
    public OnlineColumnarGenerateRequest(
            String executionId,
            Map<String, ? extends List<?>> sharedValues,
            List<String> candidateIds,
            Map<String, ? extends List<?>> candidateColumns) {
        this(executionId, sharedValues, candidateIds, candidateColumns, false);
    }

    /**
     * 同步只读借用：不复制特征列和嵌套值，候选 ID 列表仍保存快照。
     * 从构造到最后一次 generateColumnar 返回期间，所有输入及嵌套值必须保持不变，
     * 包括通过访问器取得的引用；返回后可以复用输入容器。
     */
    public static OnlineColumnarGenerateRequest borrowed(
            String executionId,
            Map<String, ? extends List<?>> sharedValues,
            List<String> candidateIds,
            Map<String, ? extends List<?>> candidateColumns) {
        return new OnlineColumnarGenerateRequest(
                executionId, sharedValues, candidateIds, candidateColumns, true);
    }

    private OnlineColumnarGenerateRequest(
            String executionId,
            Map<String, ? extends List<?>> sharedValues,
            List<String> candidateIds,
            Map<String, ? extends List<?>> candidateColumns,
            boolean borrowed) {
        this.executionId = requireText(executionId, "executionId");
        this.candidateIds = List.copyOf(Objects.requireNonNull(candidateIds, "candidateIds"));
        for (int index = 0; index < this.candidateIds.size(); index++) {
            if (this.candidateIds.get(index).isBlank()) {
                throw new IllegalArgumentException("candidateIds[" + index + "] must not be blank");
            }
        }
        Objects.requireNonNull(candidateColumns, "candidateColumns");
        candidateColumns.forEach((name, column) -> {
            Objects.requireNonNull(name, "feature name");
            Objects.requireNonNull(column, "candidate column " + name);
            if (column.size() != this.candidateIds.size()) {
                throw new IllegalArgumentException("Candidate column " + name + " size " + column.size()
                        + " does not match candidate count " + this.candidateIds.size());
            }
        });
        this.sharedValues = borrowed
                ? FeatureValueCollections.borrowedFeatureMap(sharedValues) : snapshot(sharedValues);
        this.candidateColumns = borrowed
                ? FeatureValueCollections.borrowedFeatureMap(candidateColumns) : snapshot(candidateColumns);
    }

    public String executionId() { return executionId; }
    public Map<String, List<?>> sharedValues() { return sharedValues; }
    public List<String> candidateIds() { return candidateIds; }
    public int candidateCount() { return candidateIds.size(); }
    public Map<String, List<?>> candidateColumns() { return candidateColumns; }

    private static Map<String, List<?>> snapshot(Map<String, ? extends List<?>> values) {
        Objects.requireNonNull(values, "feature values");
        Map<String, List<?>> copy = new LinkedHashMap<>(FeatureValueCollections.mapCapacity(values.size()));
        values.forEach((name, list) -> copy.put(Objects.requireNonNull(name, "feature name"),
                snapshotList(Objects.requireNonNull(list, "feature values"))));
        return Collections.unmodifiableMap(copy);
    }

    private static List<?> snapshotList(List<?> values) {
        List<Object> copy = new ArrayList<>(values.size());
        for (Object value : values) copy.add(snapshotValue(value));
        return Collections.unmodifiableList(copy);
    }

    private static Object snapshotValue(Object value) {
        if (value instanceof List<?> list) return snapshotList(list);
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>(FeatureValueCollections.mapCapacity(map.size()));
            map.forEach((key, nested) -> copy.put(key, snapshotValue(nested)));
            return Collections.unmodifiableMap(copy);
        }
        return value;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
