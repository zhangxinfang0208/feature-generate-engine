package com.example.featuredag.api;

public final class FeatureDagInitializationException extends RuntimeException {
    private final String featureSetName;
    private final String version;
    private final String planId;
    private final String featureName;

    public FeatureDagInitializationException(
            String detail,
            String featureSetName,
            String version,
            String planId,
            String featureName,
            Throwable cause) {
        super(format(detail, featureSetName, version, planId, featureName), cause);
        this.featureSetName = featureSetName;
        this.version = version;
        this.planId = planId;
        this.featureName = featureName;
    }

    public String featureSetName() { return featureSetName; }
    public String version() { return version; }
    public String planId() { return planId; }
    public String featureName() { return featureName; }

    private static String format(
            String detail, String featureSetName, String version, String planId, String featureName) {
        return "Failed to initialize feature DAG [featureSet=" + featureSetName
                + ", version=" + version + ", planId=" + planId
                + ", feature=" + featureName + "]: " + detail;
    }
}
