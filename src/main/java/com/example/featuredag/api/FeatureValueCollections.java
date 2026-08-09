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
        List<Object> result = new ArrayList<>(1);
        result.add(value);
        return Collections.unmodifiableList(result);
    }

    static Map<String, List<?>> immutableFeatureMap(Map<String, ? extends List<?>> values) {
        Objects.requireNonNull(values, "feature values");
        Map<String, List<?>> result = new LinkedHashMap<>();
        values.forEach((name, featureValues) -> result.put(
                Objects.requireNonNull(name, "feature name"), immutableList(featureValues)));
        return Collections.unmodifiableMap(result);
    }

    static List<Map<String, List<?>>> immutableCandidates(
            List<? extends Map<String, ? extends List<?>>> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        return candidates.stream()
                .map(FeatureValueCollections::immutableFeatureMap)
                .toList();
    }
}
