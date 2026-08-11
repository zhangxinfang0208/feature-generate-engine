package com.example.featuredag.runtime;

/** 有界异步观察器的运行状态，用于监控积压、丢弃和下游导出失败。 */
public record AsyncObserverStats(
        long accepted,
        long dropped,
        long exported,
        long exportFailures,
        int pending) {

    public AsyncObserverStats {
        if (accepted < 0 || dropped < 0 || exported < 0 || exportFailures < 0 || pending < 0) {
            throw new IllegalArgumentException("Async observer counters must not be negative");
        }
    }
}
