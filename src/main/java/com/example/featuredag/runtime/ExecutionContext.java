package com.example.featuredag.runtime;

import com.example.featuredag.physical.ExecutionEnvironment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ExecutionContext {
    private final String executionId;
    private final ExecutionEnvironment environment;
    private final Map<String, Object> sharedSourceValues;
    private final List<Map<String, Object>> candidates;
    private final Map<String, ValueHandle> resultSlots = new LinkedHashMap<>();
    private final Map<String, Object> cacheRegistry = new LinkedHashMap<>();
    private final Map<String, RuntimeNodeState> nodeStates = new LinkedHashMap<>();
    private Integer rawSequenceLength;
    private String firstRawSequenceFeature;

    private ExecutionContext(
            String executionId,
            ExecutionEnvironment environment,
            Map<String, Object> sharedSourceValues,
            List<Map<String, Object>> candidates) {
        this.executionId = Objects.requireNonNull(executionId, "executionId");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.sharedSourceValues = Collections.unmodifiableMap(new LinkedHashMap<>(sharedSourceValues));
        this.candidates = candidates.stream()
                .map(candidate -> Collections.unmodifiableMap(new LinkedHashMap<>(candidate)))
                .toList();
    }

    public static ExecutionContext offlineRow(String executionId, Map<String, Object> rowValues) {
        return new ExecutionContext(executionId, ExecutionEnvironment.OFFLINE, rowValues, List.of());
    }

    public static ExecutionContext onlineRequest(
            String requestId,
            Map<String, Object> userAndSceneValues,
            List<Map<String, Object>> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        return new ExecutionContext(requestId, ExecutionEnvironment.ONLINE, userAndSceneValues, candidates);
    }

    public String executionId() { return executionId; }
    public ExecutionEnvironment environment() { return environment; }
    public Map<String, Object> sharedSourceValues() { return sharedSourceValues; }
    public List<Map<String, Object>> candidates() { return candidates; }
    public int candidateCount() { return candidates.size(); }
    public Map<String, ValueHandle> resultSlots() { return resultSlots; }
    public Map<String, Object> cacheRegistry() { return cacheRegistry; }
    public Map<String, RuntimeNodeState> nodeStates() { return nodeStates; }

    public RuntimeNodeState state(String physicalNodeId) {
        return nodeStates.computeIfAbsent(physicalNodeId, RuntimeNodeState::new);
    }

    void registerRawSequence(String featureName, int size) {
        Objects.requireNonNull(featureName, "featureName");
        if (rawSequenceLength == null) {
            rawSequenceLength = size;
            firstRawSequenceFeature = featureName;
            return;
        }
        if (rawSequenceLength != size) {
            throw new IllegalArgumentException(
                    "Raw sequence length mismatch: firstFeature=" + firstRawSequenceFeature
                            + ", feature=" + featureName
                            + ", expected=" + rawSequenceLength
                            + ", actual=" + size);
        }
    }
}
