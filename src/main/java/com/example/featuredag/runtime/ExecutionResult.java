package com.example.featuredag.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ExecutionResult {
    private final Map<String, ValueHandle> featureOutputs;
    private final Map<String, RuntimeNodeState> nodeStates;

    public ExecutionResult(
            Map<String, ValueHandle> featureOutputs,
            Map<String, RuntimeNodeState> nodeStates) {
        this.featureOutputs = Collections.unmodifiableMap(new LinkedHashMap<>(featureOutputs));
        this.nodeStates = Collections.unmodifiableMap(new LinkedHashMap<>(nodeStates));
    }

    public Map<String, ValueHandle> featureOutputs() { return featureOutputs; }
    public Map<String, RuntimeNodeState> nodeStates() { return nodeStates; }

    public ValueHandle feature(String featureName) {
        ValueHandle value = featureOutputs.get(featureName);
        if (value == null) throw new IllegalArgumentException("No output feature: " + featureName);
        return value;
    }
}
