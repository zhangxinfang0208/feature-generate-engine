package com.example.featuredag.runtime;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 物理节点运行状态（运行时观测）：NOT_STARTED → RUNNING → SUCCESS/FAILED 的状态机，
 * 记录耗时、缓存命中、去重计数等诊断信息，随 ExecutionResult 返回。
 */
public final class RuntimeNodeState {
    private final String physicalNodeId;
    private ExecutionStatus status = ExecutionStatus.NOT_STARTED;
    private ValueHandle resultHandle;
    private boolean cacheHit;
    private String cacheSource;
    private long durationNanos;
    private long allocatedBytes;
    private int dedupInputCount;
    private int uniqueInputCount;
    private Throwable error;
    private boolean fallbackUsed;
    private final EnumMap<CacheKind, MutableCacheStats> cacheStats =
            new EnumMap<>(CacheKind.class);

    public RuntimeNodeState(String physicalNodeId) {
        this.physicalNodeId = physicalNodeId;
    }

    public String physicalNodeId() { return physicalNodeId; }
    public ExecutionStatus status() { return status; }
    public ValueHandle resultHandle() { return resultHandle; }
    public boolean cacheHit() { return cacheHit; }
    public String cacheSource() { return cacheSource; }
    public long durationNanos() { return durationNanos; }
    public long allocatedBytes() { return allocatedBytes; }
    public int dedupInputCount() { return dedupInputCount; }
    public int uniqueInputCount() { return uniqueInputCount; }
    public Throwable error() { return error; }
    public boolean fallbackUsed() { return fallbackUsed; }
    public Map<CacheKind, CacheStats> cacheStats() {
        if (cacheStats.isEmpty()) return Map.of();
        EnumMap<CacheKind, CacheStats> result = new EnumMap<>(CacheKind.class);
        for (Map.Entry<CacheKind, MutableCacheStats> entry : cacheStats.entrySet()) {
            result.put(entry.getKey(), entry.getValue().snapshot());
        }
        return Collections.unmodifiableMap(result);
    }

    void markRunning() { this.status = ExecutionStatus.RUNNING; }
    void markSuccess(ValueHandle result, long durationNanos) {
        this.status = ExecutionStatus.SUCCESS;
        this.resultHandle = result;
        this.durationNanos = durationNanos;
    }
    void markFailure(Throwable error, long durationNanos) {
        this.status = ExecutionStatus.FAILED;
        this.error = error;
        this.durationNanos = durationNanos;
    }
    void recordCacheLookup(CacheKind kind, boolean hit) {
        cacheStats.computeIfAbsent(kind, ignored -> new MutableCacheStats()).recordLookup(hit);
        if (hit) {
            this.cacheHit = true;
            this.cacheSource = switch (kind) {
                case SEQUENCE_INDEX -> "REQUEST_INDEX";
                case CANDIDATE_KEY, SEQUENCE_COUNT -> "CANDIDATE_KEY";
            };
        }
    }
    void recordCachePut(CacheKind kind) {
        cacheStats.computeIfAbsent(kind, ignored -> new MutableCacheStats()).recordPut();
    }
    void setAllocatedBytes(long value) { this.allocatedBytes = value; }
    void setDedupCounts(int inputCount, int uniqueCount) {
        this.dedupInputCount = inputCount;
        this.uniqueInputCount = uniqueCount;
    }
    void setFallbackUsed(boolean value) { this.fallbackUsed = value; }

    RuntimeNodeState snapshot() {
        RuntimeNodeState copy = new RuntimeNodeState(physicalNodeId);
        copy.status = status;
        copy.resultHandle = resultHandle;
        copy.cacheHit = cacheHit;
        copy.cacheSource = cacheSource;
        copy.durationNanos = durationNanos;
        copy.allocatedBytes = allocatedBytes;
        copy.dedupInputCount = dedupInputCount;
        copy.uniqueInputCount = uniqueInputCount;
        copy.error = error;
        copy.fallbackUsed = fallbackUsed;
        for (Map.Entry<CacheKind, MutableCacheStats> entry : cacheStats.entrySet()) {
            copy.cacheStats.put(entry.getKey(), entry.getValue().copy());
        }
        return copy;
    }
}
