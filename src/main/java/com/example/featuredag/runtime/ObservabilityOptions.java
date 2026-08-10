package com.example.featuredag.runtime;

import java.time.Duration;
import java.util.Objects;

/**
 * 可热更新的运行时观测采集策略。
 * 正常请求按 executionId 确定性采样；失败和慢请求可绕过采样强制采集。
 */
public final class ObservabilityOptions {
    private final boolean enabled;
    private final double sampleRate;
    private final boolean captureFailuresAlways;
    private final long slowRequestThresholdNanos;
    private final ObservationDetailLevel detailLevel;

    private ObservabilityOptions(Builder builder) {
        this.enabled = builder.enabled;
        this.sampleRate = requireSampleRate(builder.sampleRate);
        this.captureFailuresAlways = builder.captureFailuresAlways;
        this.slowRequestThresholdNanos = requireThreshold(builder.slowRequestThreshold);
        this.detailLevel = Objects.requireNonNull(builder.detailLevel, "detailLevel");
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean enabled() { return enabled; }
    public double sampleRate() { return sampleRate; }
    public boolean captureFailuresAlways() { return captureFailuresAlways; }
    public long slowRequestThresholdNanos() { return slowRequestThresholdNanos; }
    public ObservationDetailLevel detailLevel() { return detailLevel; }

    public boolean canCaptureAnyRequest() {
        return enabled
                && (sampleRate > 0.0
                        || captureFailuresAlways
                        || slowRequestThresholdNanos > 0);
    }

    public Builder toBuilder() {
        return new Builder()
                .enabled(enabled)
                .sampleRate(sampleRate)
                .captureFailuresAlways(captureFailuresAlways)
                .slowRequestThreshold(Duration.ofNanos(slowRequestThresholdNanos))
                .detailLevel(detailLevel);
    }

    private static double requireSampleRate(double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("sampleRate must be between 0.0 and 1.0");
        }
        return value;
    }

    private static long requireThreshold(Duration value) {
        Objects.requireNonNull(value, "slowRequestThreshold");
        if (value.isNegative()) {
            throw new IllegalArgumentException("slowRequestThreshold must not be negative");
        }
        try {
            return value.toNanos();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("slowRequestThreshold is too large", error);
        }
    }

    public static final class Builder {
        private boolean enabled = true;
        private double sampleRate = 1.0;
        private boolean captureFailuresAlways = true;
        private Duration slowRequestThreshold = Duration.ZERO;
        private ObservationDetailLevel detailLevel = ObservationDetailLevel.NODE;

        public Builder enabled(boolean value) {
            this.enabled = value;
            return this;
        }

        public Builder sampleRate(double value) {
            this.sampleRate = value;
            return this;
        }

        public Builder captureFailuresAlways(boolean value) {
            this.captureFailuresAlways = value;
            return this;
        }

        public Builder slowRequestThreshold(Duration value) {
            this.slowRequestThreshold = Objects.requireNonNull(value, "slowRequestThreshold");
            return this;
        }

        public Builder detailLevel(ObservationDetailLevel value) {
            this.detailLevel = Objects.requireNonNull(value, "detailLevel");
            return this;
        }

        public ObservabilityOptions build() {
            return new ObservabilityOptions(this);
        }
    }
}
