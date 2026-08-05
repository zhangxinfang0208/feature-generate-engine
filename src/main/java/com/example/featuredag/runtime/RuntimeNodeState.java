package com.example.featuredag.runtime;

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
    void markCacheHit(String source) { this.cacheHit = true; this.cacheSource = source; }
    void setAllocatedBytes(long value) { this.allocatedBytes = value; }
    void setDedupCounts(int inputCount, int uniqueCount) {
        this.dedupInputCount = inputCount;
        this.uniqueInputCount = uniqueCount;
    }
    void setFallbackUsed(boolean value) { this.fallbackUsed = value; }
}
