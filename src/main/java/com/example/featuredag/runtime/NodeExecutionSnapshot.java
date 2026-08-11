package com.example.featuredag.runtime;

import com.example.featuredag.physical.CachePolicy;
import com.example.featuredag.physical.ExecutionStage;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** 不携带结果值和异常对象的物理节点诊断快照。 */
public record NodeExecutionSnapshot(
        String physicalNodeId,
        String executorId,
        ExecutionStage executionStage,
        CachePolicy cachePolicy,
        ExecutionStatus status,
        long durationNanos,
        Map<CacheKind, CacheStats> cacheStats,
        int inputCount,
        int uniqueInputCount,
        boolean fallbackUsed,
        String errorType) {

    public NodeExecutionSnapshot {
        Objects.requireNonNull(physicalNodeId, "physicalNodeId");
        Objects.requireNonNull(executorId, "executorId");
        Objects.requireNonNull(executionStage, "executionStage");
        Objects.requireNonNull(cachePolicy, "cachePolicy");
        Objects.requireNonNull(status, "status");
        if (durationNanos < 0) throw new IllegalArgumentException("durationNanos must not be negative");
        if (inputCount < 0 || uniqueInputCount < 0 || uniqueInputCount > inputCount) {
            throw new IllegalArgumentException("Invalid dedup counters");
        }
        Objects.requireNonNull(cacheStats, "cacheStats");
        if (cacheStats.isEmpty()) {
            cacheStats = Map.of();
        } else {
            EnumMap<CacheKind, CacheStats> copy = new EnumMap<>(CacheKind.class);
            copy.putAll(cacheStats);
            cacheStats = Collections.unmodifiableMap(copy);
        }
    }
}
