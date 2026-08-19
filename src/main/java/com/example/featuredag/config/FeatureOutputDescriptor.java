package com.example.featuredag.config;

import java.util.Objects;

public record FeatureOutputDescriptor(
        String featureName,
        String storeName,
        int order,
        int declarationIndex,
        Integer sequenceMaxLength) {
    public FeatureOutputDescriptor(
            String featureName,
            String storeName,
            int order,
            int declarationIndex) {
        this(featureName, storeName, order, declarationIndex, null);
    }

    public FeatureOutputDescriptor {
        featureName = requireText(featureName, "featureName");
        storeName = requireText(storeName, "storeName");
        if (declarationIndex < 0) {
            throw new IllegalArgumentException("declarationIndex must not be negative");
        }
        if (sequenceMaxLength != null && sequenceMaxLength <= 0) {
            throw new IllegalArgumentException("sequenceMaxLength must be positive");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String result = value.trim();
        if (result.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return result;
    }
}
