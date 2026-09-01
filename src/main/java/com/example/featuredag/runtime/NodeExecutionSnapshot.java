package com.example.featuredag.runtime;

import com.example.featuredag.operator.BatchDomain;
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
        OperatorInvocationKind operatorInvocationKind,
        BatchDomain batchDomain,
        int batchRowCount,
        ExecutionStage executionStage,
        CachePolicy cachePolicy,
        ExecutionStatus status,
        long durationNanos,
        Map<CacheKind, CacheStats> cacheStats,
        int inputCount,
        int uniqueInputCount,
        boolean fallbackUsed,
        int operatorFailureCount,
        int fallbackCount,
        String errorType) {

    public NodeExecutionSnapshot {
        Objects.requireNonNull(physicalNodeId, "physicalNodeId");
        Objects.requireNonNull(executorId, "executorId");
        if (batchRowCount < 0) {
            throw new IllegalArgumentException("batchRowCount must not be negative");
        }
        if (operatorInvocationKind == null) {
            if (batchDomain != null || batchRowCount != 0) {
                throw new IllegalArgumentException(
                        "Non-operator nodes must not contain Batch diagnostics");
            }
        } else if (operatorInvocationKind.isBatch()) {
            Objects.requireNonNull(batchDomain, "batchDomain");
        } else if (batchDomain != null || batchRowCount != 0) {
            throw new IllegalArgumentException(
                    "Non-Batch invocation must not contain Batch diagnostics");
        }
        Objects.requireNonNull(executionStage, "executionStage");
        Objects.requireNonNull(cachePolicy, "cachePolicy");
        Objects.requireNonNull(status, "status");
        if (durationNanos < 0) throw new IllegalArgumentException("durationNanos must not be negative");
        if (inputCount < 0 || uniqueInputCount < 0 || uniqueInputCount > inputCount) {
            throw new IllegalArgumentException("Invalid dedup counters");
        }
        if (operatorFailureCount < 0 || fallbackCount < 0) {
            throw new IllegalArgumentException("Recovery counters must not be negative");
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

    /** 保留新增调用路径诊断前的构造形式，非算子节点和旧调用方默认无调用路径。 */
    public NodeExecutionSnapshot(
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
        this(
                physicalNodeId,
                executorId,
                null,
                null,
                0,
                executionStage,
                cachePolicy,
                status,
                durationNanos,
                cacheStats,
                inputCount,
                uniqueInputCount,
                fallbackUsed,
                0,
                0,
                errorType);
    }
}
