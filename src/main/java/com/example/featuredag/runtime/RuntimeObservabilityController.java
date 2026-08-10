package com.example.featuredag.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** 通过原子替换采集策略支持运行中动态开关，不要求重建 FeatureDagEngine。 */
public final class RuntimeObservabilityController {
    private final AtomicReference<ObservabilityOptions> options;

    public RuntimeObservabilityController(ObservabilityOptions initialOptions) {
        this.options = new AtomicReference<>(
                Objects.requireNonNull(initialOptions, "initialOptions"));
    }

    public ObservabilityOptions options() {
        return options.get();
    }

    public void update(ObservabilityOptions value) {
        options.set(Objects.requireNonNull(value, "options"));
    }

    public void setEnabled(boolean enabled) {
        options.updateAndGet(current -> current.toBuilder().enabled(enabled).build());
    }
}
