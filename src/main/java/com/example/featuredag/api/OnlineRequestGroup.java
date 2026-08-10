package com.example.featuredag.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 一个在线批分组：一份 USER/SCENE 共享输入及其所属的 ITEM candidates。 */
public final class OnlineRequestGroup {
    private final String executionId;
    private final Map<String, List<?>> sharedValues;
    private final List<Map<String, List<?>>> candidates;

    public OnlineRequestGroup(
            String executionId,
            Map<String, List<?>> sharedValues,
            List<? extends Map<String, ? extends List<?>>> candidates) {
        this.executionId = requireText(executionId, "executionId");
        this.sharedValues = FeatureValueCollections.immutableFeatureMap(sharedValues);
        this.candidates = FeatureValueCollections.immutableCandidates(candidates);
    }

    public String executionId() { return executionId; }
    public Map<String, List<?>> sharedValues() { return sharedValues; }
    public List<Map<String, List<?>>> candidates() { return candidates; }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
