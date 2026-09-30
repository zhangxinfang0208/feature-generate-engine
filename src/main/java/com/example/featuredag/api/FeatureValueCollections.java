package com.example.featuredag.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class FeatureValueCollections {
    private FeatureValueCollections() {}

    static List<?> immutableList(List<?> values) {
        Objects.requireNonNull(values, "feature values");
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    static List<?> singleton(Object value) {
        return Collections.singletonList(value);
    }

    /** 以默认负载因子容纳已知元素数量，避免输出组装时反复扩容。 */
    static int mapCapacity(int mappings) {
        if (mappings < 0) throw new IllegalArgumentException("mappings must be non-negative");
        return (int) Math.min(Integer.MAX_VALUE, (long) mappings * 4 / 3 + 1);
    }

    static Map<String, List<?>> immutableFeatureMap(Map<String, ? extends List<?>> values) {
        Objects.requireNonNull(values, "feature values");
        Map<String, List<?>> result = new LinkedHashMap<>(mapCapacity(values.size()));
        values.forEach((name, featureValues) -> result.put(
                Objects.requireNonNull(name, "feature name"), immutableList(featureValues)));
        return Collections.unmodifiableMap(result);
    }

    /** 只借用特征 Map/List；校验不遍历序列元素，不复制特征项或序列数据。 */
    @SuppressWarnings("unchecked")
    static Map<String, List<?>> borrowedFeatureMap(Map<String, ? extends List<?>> values) {
        Objects.requireNonNull(values, "feature values");
        values.forEach((name, list) -> {
            Objects.requireNonNull(name, "feature name");
            Objects.requireNonNull(list, "feature values");
        });
        // 仅经只读 Map 视图对外提供类型协变，不向借用容器写入元素。
        return Collections.unmodifiableMap((Map<String, List<?>>) values);
    }

    static List<Map<String, List<?>>> borrowedFeatureRows(
            List<? extends Map<String, ? extends List<?>>> rows) {
        Objects.requireNonNull(rows, "rows");
        List<Map<String, List<?>>> views = new ArrayList<>(rows.size());
        for (Map<String, ? extends List<?>> row : rows) views.add(borrowedFeatureMap(row));
        return Collections.unmodifiableList(views);
    }

    static List<Map<String, List<?>>> immutableCandidates(
            List<? extends Map<String, ? extends List<?>>> candidates) {
        return immutableFeatureRows(candidates, "candidates");
    }

    static List<Map<String, List<?>>> immutableFeatureRows(
            List<? extends Map<String, ? extends List<?>>> rows) {
        return immutableFeatureRows(rows, "rows");
    }

    private static List<Map<String, List<?>>> immutableFeatureRows(
            List<? extends Map<String, ? extends List<?>>> rows,
            String field) {
        Objects.requireNonNull(rows, field);
        return rows.stream()
                .map(FeatureValueCollections::immutableFeatureMap)
                .toList();
    }
}
