package com.example.featuredag.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 有界、线程安全的内存观察器，适用于自测试、压测断言和临时诊断。 */
public final class InMemoryRuntimeObserver implements RuntimeObserver {
    private final int capacity;
    private final ArrayDeque<ExecutionDiagnostics> entries = new ArrayDeque<>();
    private long receivedCount;

    public InMemoryRuntimeObserver(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    @Override
    public synchronized void onExecutionCompleted(ExecutionDiagnostics diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics");
        receivedCount++;
        if (entries.size() == capacity) entries.removeFirst();
        entries.addLast(diagnostics);
    }

    public synchronized long receivedCount() {
        return receivedCount;
    }

    public synchronized List<ExecutionDiagnostics> snapshots() {
        return List.copyOf(new ArrayList<>(entries));
    }

    public synchronized ExecutionDiagnostics latest() {
        if (entries.isEmpty()) throw new IllegalStateException("No execution diagnostics available");
        return entries.getLast();
    }

    public synchronized void clear() {
        entries.clear();
        receivedCount = 0;
    }
}
