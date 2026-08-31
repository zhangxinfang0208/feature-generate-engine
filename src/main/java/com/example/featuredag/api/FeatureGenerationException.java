package com.example.featuredag.api;

import java.util.List;
import java.util.Objects;

public final class FeatureGenerationException extends RuntimeException {
    private final String planId;
    private final String executionId;
    private final String featureName;
    private final List<String> featureNames;

    public FeatureGenerationException(
            String detail,
            String planId,
            String executionId,
            String featureName,
            Throwable cause) {
        this(
                detail,
                planId,
                executionId,
                featureName == null ? List.of() : List.of(featureName),
                cause);
    }

    public static FeatureGenerationException forFeatureNames(
            String detail,
            String planId,
            String executionId,
            List<String> featureNames,
            Throwable cause) {
        return new FeatureGenerationException(
                detail, planId, executionId, featureNames, cause);
    }

    private FeatureGenerationException(
            String detail,
            String planId,
            String executionId,
            List<String> featureNames,
            Throwable cause) {
        super(format(detail, planId, executionId, featureNames), cause);
        this.planId = planId;
        this.executionId = executionId;
        this.featureNames = List.copyOf(Objects.requireNonNull(featureNames, "featureNames"));
        this.featureName = this.featureNames.size() == 1 ? this.featureNames.getFirst() : null;
    }

    public String planId() { return planId; }
    public String executionId() { return executionId; }
    public String featureName() { return featureName; }
    public List<String> featureNames() { return featureNames; }

    private static String format(
            String detail, String planId, String executionId, List<String> featureNames) {
        String featureContext;
        if (featureNames.isEmpty()) {
            featureContext = "feature=null";
        } else if (featureNames.size() == 1) {
            featureContext = "feature=" + featureNames.getFirst();
        } else {
            featureContext = "features=" + featureNames;
        }
        return "Failed to generate features [planId=" + planId
                + ", executionId=" + executionId + ", " + featureContext + "]: " + detail;
    }
}
