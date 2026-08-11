package com.example.featuredag.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 将请求线程上的观测回调转换为非阻塞入队，并由单个守护线程批量导出。
 * 队列满或观察器关闭后只增加 dropped，不阻塞也不影响 DAG 执行。
 */
public final class AsyncRuntimeObserver implements RuntimeObserver, AutoCloseable {
    private static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final AtomicLong THREAD_SEQUENCE = new AtomicLong();

    private final ArrayBlockingQueue<Entry> queue;
    private final int batchSize;
    private final long pollIntervalNanos;
    private final Consumer<List<ExecutionDiagnostics>> sink;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong exported = new AtomicLong();
    private final AtomicLong exportFailures = new AtomicLong();
    private final Object drainMonitor = new Object();
    private final Thread worker;

    public AsyncRuntimeObserver(
            int queueCapacity,
            int batchSize,
            Duration pollInterval,
            Consumer<List<ExecutionDiagnostics>> sink) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.batchSize = batchSize;
        this.pollIntervalNanos = requirePositiveNanos(pollInterval, "pollInterval");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.worker = new Thread(
                this::runWorker,
                "feature-dag-observer-" + THREAD_SEQUENCE.incrementAndGet());
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public void onExecutionCompleted(ExecutionDiagnostics diagnostics) {
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (closed.get()) {
            dropped.incrementAndGet();
            return;
        }
        Entry entry = new Entry(diagnostics);
        if (!queue.offer(entry)) {
            dropped.incrementAndGet();
            return;
        }
        // 与 close 竞争时，要么 worker 接管该条数据，要么请求线程将其移除并计为丢弃。
        if (closed.get() && queue.remove(entry)) {
            dropped.incrementAndGet();
            return;
        }
        accepted.incrementAndGet();
    }

    public AsyncObserverStats stats() {
        long pending = Math.max(0, accepted.get() - exported.get() - exportFailures.get());
        return new AsyncObserverStats(
                accepted.get(),
                dropped.get(),
                exported.get(),
                exportFailures.get(),
                pending > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) pending);
    }

    /** 等待已接受的数据导出完成；不会接管或关闭观察器。 */
    public boolean awaitDrained(Duration timeout) {
        long timeoutNanos = requireNonNegativeNanos(timeout, "timeout");
        long deadline = saturatedAdd(System.nanoTime(), timeoutNanos);
        long target = accepted.get();
        synchronized (drainMonitor) {
            while (exported.get() + exportFailures.get() < target) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                try {
                    TimeUnit.NANOSECONDS.timedWait(drainMonitor, remaining);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    @Override
    public void close() {
        close(DEFAULT_CLOSE_TIMEOUT);
    }

    /** 停止接收新数据并在超时范围内尽量排空队列。 */
    public boolean close(Duration timeout) {
        long timeoutNanos = requireNonNegativeNanos(timeout, "timeout");
        closed.set(true);
        worker.interrupt();
        long deadline = saturatedAdd(System.nanoTime(), timeoutNanos);
        while (worker.isAlive()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            try {
                TimeUnit.NANOSECONDS.timedJoin(worker, remaining);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private void runWorker() {
        List<ExecutionDiagnostics> batch = new ArrayList<>(batchSize);
        while (!closed.get() || !queue.isEmpty()) {
            try {
                Entry first = queue.poll(pollIntervalNanos, TimeUnit.NANOSECONDS);
                if (first == null) continue;
                batch.add(first.diagnostics);
                while (batch.size() < batchSize) {
                    Entry next = queue.poll();
                    if (next == null) break;
                    batch.add(next.diagnostics);
                }
                export(batch);
            } catch (InterruptedException ignored) {
                // close 通过中断唤醒空闲 worker；循环会继续排空已接受的数据。
            }
        }
        signalDrainWaiters();
    }

    private void export(List<ExecutionDiagnostics> batch) {
        int size = batch.size();
        try {
            sink.accept(List.copyOf(batch));
            exported.addAndGet(size);
        } catch (RuntimeException ignored) {
            exportFailures.addAndGet(size);
        } finally {
            batch.clear();
            signalDrainWaiters();
        }
    }

    private void signalDrainWaiters() {
        synchronized (drainMonitor) {
            drainMonitor.notifyAll();
        }
    }

    private static long requirePositiveNanos(Duration value, String field) {
        long nanos = requireNonNegativeNanos(value, field);
        if (nanos == 0) throw new IllegalArgumentException(field + " must be positive");
        return nanos;
    }

    private static long requireNonNegativeNanos(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isNegative()) throw new IllegalArgumentException(field + " must not be negative");
        try {
            return value.toNanos();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(field + " is too large", error);
        }
    }

    private static long saturatedAdd(long left, long right) {
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0) return Long.MAX_VALUE;
        return result;
    }

    private static final class Entry {
        private final ExecutionDiagnostics diagnostics;

        private Entry(ExecutionDiagnostics diagnostics) {
            this.diagnostics = diagnostics;
        }
    }
}
