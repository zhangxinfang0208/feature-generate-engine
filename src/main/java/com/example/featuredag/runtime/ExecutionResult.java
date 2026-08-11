package com.example.featuredag.runtime;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 执行产物（运行时）：特征名 → 值句柄 的输出映射、各物理节点运行状态和请求级缓存统计，
 * 供引擎编码输出与低层观测诊断使用。
 */
public final class ExecutionResult {
    private final Map<String, ValueHandle> featureOutputs;
    private final Map<String, RuntimeNodeState> nodeStates;
    private final Map<CacheKind, CacheStats> cacheStats;

    public ExecutionResult(
            Map<String, ValueHandle> featureOutputs,
            Map<String, RuntimeNodeState> nodeStates) {
        this(featureOutputs, nodeStates, Map.of());
    }

    public ExecutionResult(
            Map<String, ValueHandle> featureOutputs,
            Map<String, RuntimeNodeState> nodeStates,
            Map<CacheKind, CacheStats> cacheStats) {
        this.featureOutputs = Collections.unmodifiableMap(new LinkedHashMap<>(featureOutputs));
        Map<String, RuntimeNodeState> stateSnapshots = new LinkedHashMap<>();
        for (Map.Entry<String, RuntimeNodeState> entry : nodeStates.entrySet()) {
            stateSnapshots.put(entry.getKey(), entry.getValue().snapshot());
        }
        this.nodeStates = Collections.unmodifiableMap(stateSnapshots);
        if (cacheStats.isEmpty()) {
            this.cacheStats = Map.of();
        } else {
            EnumMap<CacheKind, CacheStats> copy = new EnumMap<>(CacheKind.class);
            copy.putAll(cacheStats);
            this.cacheStats = Collections.unmodifiableMap(copy);
        }
    }

    public Map<String, ValueHandle> featureOutputs() { return featureOutputs; }
    public Map<String, RuntimeNodeState> nodeStates() { return nodeStates; }
    public Map<CacheKind, CacheStats> cacheStats() { return cacheStats; }

    public ValueHandle feature(String featureName) {
        ValueHandle value = featureOutputs.get(featureName);
        if (value == null) throw new IllegalArgumentException("No output feature: " + featureName);
        return value;
    }
}
