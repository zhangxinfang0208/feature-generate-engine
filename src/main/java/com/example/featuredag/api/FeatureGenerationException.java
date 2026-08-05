package com.example.featuredag.api;

public final class FeatureGenerationException extends RuntimeException {
    private final String planId;
    private final String executionId;
    private final String featureName;

    public FeatureGenerationException(
            String detail,
            String planId,
            String executionId,
            String featureName,
            Throwable cause) {
        super(format(detail, planId, executionId, featureName), cause);
        this.planId = planId;
        this.executionId = executionId;
        this.featureName = featureName;
    }

    public String planId() { return planId; }
    public String executionId() { return executionId; }
    public String featureName() { return featureName; }

    private static String format(
            String detail, String planId, String executionId, String featureName) {
        return "Failed to generate features [planId=" + planId
                + ", executionId=" + executionId + ", feature=" + featureName + "]: " + detail;
    }
}
