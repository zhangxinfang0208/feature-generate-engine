package com.example.featuredag.runtime;

import java.util.Objects;

/** 算子失败到达无 dft 的特征边界时抛出的可定位异常。 */
public final class FeatureEvaluationException extends RuntimeException {
    private final String featureName;
    private final String location;

    FeatureEvaluationException(
            String featureName,
            String location,
            RuntimeException cause) {
        super("Feature " + Objects.requireNonNull(featureName, "featureName")
                + " evaluation failed at " + Objects.requireNonNull(location, "location"),
                Objects.requireNonNull(cause, "cause"));
        this.featureName = featureName;
        this.location = location;
    }

    public String featureName() {
        return featureName;
    }

    public String location() {
        return location;
    }
}
