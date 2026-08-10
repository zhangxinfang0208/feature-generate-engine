package com.example.featuredag.runtime;

import com.example.featuredag.physical.ExecutionEnvironment;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一次公共 generate 调用的不可变诊断产物。
 * 仅包含结构、计数和耗时，不包含特征值、缓存 key 或 Throwable。
 */
public record ExecutionDiagnostics(
        String planId,
        String featureSetName,
        String version,
        String executionId,
        ExecutionEnvironment environment,
        ExecutionStatus status,
        ExecutionPhase failurePhase,
        String errorType,
        long totalDurationNanos,
        long decodeDurationNanos,
        long runtimeDurationNanos,
        long encodeDurationNanos,
        int groupCount,
        int candidateCount,
        int offlineRowCount,
        int sourceSequenceCount,
        long sourceSequenceElementCount,
        int maxSourceSequenceLength,
        int physicalNodeCount,
        int logicalNodeCount,
        int fusedPhysicalNodeCount,
        Map<CacheKind, CacheStats> cacheStats,
        List<NodeExecutionSnapshot> nodes) {

    public ExecutionDiagnostics {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(featureSetName, "featureSetName");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(failurePhase, "failurePhase");
        if (totalDurationNanos < 0 || decodeDurationNanos < 0
                || runtimeDurationNanos < 0 || encodeDurationNanos < 0) {
            throw new IllegalArgumentException("Execution durations must not be negative");
        }
        if (groupCount < 0 || candidateCount < 0 || offlineRowCount < 0
                || sourceSequenceCount < 0 || sourceSequenceElementCount < 0
                || maxSourceSequenceLength < 0
                || physicalNodeCount < 0 || logicalNodeCount < 0
                || fusedPhysicalNodeCount < 0) {
            throw new IllegalArgumentException("Execution counters must not be negative");
        }
        if (maxSourceSequenceLength > sourceSequenceElementCount) {
            throw new IllegalArgumentException(
                    "Maximum source sequence length exceeds total sequence elements");
        }
        if (status == ExecutionStatus.SUCCESS
                && (failurePhase != ExecutionPhase.NONE || errorType != null)) {
            throw new IllegalArgumentException("Successful diagnostics must not contain a failure");
        }
        if (status == ExecutionStatus.FAILED
                && (failurePhase == ExecutionPhase.NONE || errorType == null)) {
            throw new IllegalArgumentException("Failed diagnostics must identify the failure");
        }
        Objects.requireNonNull(cacheStats, "cacheStats");
        if (cacheStats.isEmpty()) {
            cacheStats = Map.of();
        } else {
            EnumMap<CacheKind, CacheStats> copy = new EnumMap<>(CacheKind.class);
            copy.putAll(cacheStats);
            cacheStats = Collections.unmodifiableMap(copy);
        }
        nodes = List.copyOf(nodes);
    }

    public long cacheLookups() {
        return cacheStats.values().stream().mapToLong(CacheStats::lookups).sum();
    }

    public long cacheHits() {
        return cacheStats.values().stream().mapToLong(CacheStats::hits).sum();
    }

    public long cacheMisses() {
        return cacheStats.values().stream().mapToLong(CacheStats::misses).sum();
    }

    public double cacheHitRate() {
        long lookups = cacheLookups();
        return lookups == 0 ? 0.0 : (double) cacheHits() / lookups;
    }
}
