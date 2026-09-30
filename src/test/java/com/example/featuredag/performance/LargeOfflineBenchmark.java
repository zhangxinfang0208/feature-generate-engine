package com.example.featuredag.performance;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.OfflineBatchGenerateRequest;
import com.example.featuredag.api.OfflineGenerateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** 显式启动的离线基准，不在 Surefire 中执行性能阈值断言。 */
public final class LargeOfflineBenchmark {
    private static volatile Object sink;

    private LargeOfflineBenchmark() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 9 && args.length != 10) throw new IllegalArgumentException(
                "outputDir warmupSeconds measureSeconds longSequenceLength batchSize poolSize single|batch engine|request profile [snapshot|borrowed]");
        Path output = Path.of(args[0]);
        int warmup = positive(args[1]), seconds = positive(args[2]), length = positive(args[3]);
        int batchSize = positive(args[4]), pool = positive(args[5]);
        String mode = choice(args[6], "single", "batch");
        String scope = choice(args[7], "engine", "request");
        boolean profile = Boolean.parseBoolean(choice(args[8], "true", "false"));
        String inputMode = args.length == 10 ? choice(args[9], "snapshot", "borrowed") : "snapshot";
        boolean borrowed = inputMode.equals("borrowed");
        if (Files.exists(output)) throw new IllegalArgumentException("Output already exists: " + output);
        Files.createDirectories(output);
        LargeOfflineWorkload workload = new LargeOfflineWorkload(length, batchSize, pool);
        long initStart = System.nanoTime();
        FeatureDagEngine engine = workload.engine();
        double initMs = (System.nanoTime() - initStart) / 1e6;
        Files.writeString(output.resolve("features.json"), workload.config());
        Files.writeString(output.resolve("physical-plan.txt"), engine.describePhysicalPlan());
        for (int i = 0; i < pool; i++) verify(workload, engine, i);
        System.out.println("OFFLINE " + mode + "/" + scope + " batch=" + batchSize + "; warmup=" + warmup + "s");
        long deadline = System.nanoTime() + Duration.ofSeconds(warmup).toNanos();
        int index = 0;
        while (System.nanoTime() < deadline) execute(workload, engine, index++ % pool, mode, scope, borrowed);
        // 预分配原始数组，测量中不装箱；上限明确失败，避免静默丢弃延迟样本。
        long[] latencies = new long[2_000_000];
        int count = 0;
        var standardBean = ManagementFactory.getThreadMXBean();
        com.sun.management.ThreadMXBean allocation = standardBean instanceof com.sun.management.ThreadMXBean bean
                && bean.isThreadAllocatedMemorySupported() ? bean : null;
        if (allocation != null && !allocation.isThreadAllocatedMemoryEnabled()) allocation.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        long elapsed, allocated;
        long[] beforeGc, afterGc;
        System.out.println("Measurement=" + seconds + "s; profile=" + profile);
        try (Recording recording = profile ? new Recording(Configuration.getConfiguration("profile")) : null) {
            if (recording != null) {
                recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
                recording.enable("jdk.ObjectAllocationSample").with("throttle", "150/s").withStackTrace();
                recording.setMaxSize(256L * 1024 * 1024);
                recording.start();
            }
            beforeGc = gc();
            long bytes = allocation == null ? -1 : allocation.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            deadline = start + Duration.ofSeconds(seconds).toNanos();
            do {
                if (count == latencies.length) throw new IllegalStateException("Too many samples; shorten measurement");
                long callStart = System.nanoTime();
                execute(workload, engine, index++ % pool, mode, scope, borrowed);
                latencies[count++] = System.nanoTime() - callStart;
            } while (System.nanoTime() < deadline);
            elapsed = System.nanoTime() - start;
            allocated = allocation == null ? -1 : allocation.getThreadAllocatedBytes(thread) - bytes;
            afterGc = gc();
            if (recording != null) { recording.stop(); recording.dump(output.resolve("measurement.jfr")); }
        }
        verify(workload, engine, 0);
        StringBuilder csv = new StringBuilder("batch,rows,latency_ms\n");
        long totalNanos = 0;
        for (int i = 0; i < count; i++) {
            totalNanos += latencies[i];
            csv.append(i).append(',').append(batchSize).append(',').append(latencies[i] / 1e6).append('\n');
        }
        Files.writeString(output.resolve("latencies.csv"), csv);
        Arrays.sort(latencies, 0, count);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("environment", "OFFLINE");
        result.put("mode", mode); result.put("scope", scope);
        result.put("input_mode", inputMode);
        result.put("scope_note", "engine=prebuilt requests; request=request construction+engine; excludes Spark/I/O/string split/output join");
        result.put("java", System.getProperty("java.runtime.version"));
        result.put("jvm_args", ManagementFactory.getRuntimeMXBean().getInputArguments());
        result.put("processors", Runtime.getRuntime().availableProcessors());
        result.put("max_heap_bytes", Runtime.getRuntime().maxMemory());
        result.put("base", 320); result.put("derived", 320); result.put("sequence_base", 160);
        result.put("long_sequence_length", length); result.put("short_sequence_length", Math.max(1, length / 4));
        result.put("shared_sequence_identity_between_rows", false);
        result.put("batch_size", batchSize); result.put("pool_size", pool); result.put("concurrency", 1);
        result.put("profile", profile); result.put("observability", false);
        result.put("init_ms", initMs); result.put("warmup_seconds", warmup);
        result.put("elapsed_seconds", elapsed / 1e9); result.put("batches", count);
        long rows = (long) count * batchSize;
        result.put("rows", rows); result.put("rows_per_second", rows * 1e9 / elapsed);
        result.put("mean_batch_ms", totalNanos / 1e6 / count);
        result.put("amortized_us_per_row", totalNanos / 1e3 / rows);
        for (double p : new double[] {0.50, 0.95, 0.99, 1.0}) {
            result.put("p" + (int) (p * 100) + "_batch_ms", latencies[(int) Math.ceil(count * p) - 1] / 1e6);
        }
        result.put("caller_allocated_bytes_per_row", allocated < 0 ? -1 : (double) allocated / rows);
        result.put("caller_allocation_mib_per_second", allocated < 0 ? -1 : allocated / 1048576.0 / (elapsed / 1e9));
        result.put("gc_count_delta", beforeGc[0] < 0 || afterGc[0] < 0 ? -1 : afterGc[0] - beforeGc[0]);
        result.put("gc_time_ms_delta", beforeGc[1] < 0 || afterGc[1] < 0 ? -1 : afterGc[1] - beforeGc[1]);
        result.put("correctness", "all pool rows checked against independent oracle and single/batch equivalence before measurement; first batch rechecked after");
        String json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result);
        Files.writeString(output.resolve("summary.json"), json);
        System.out.println(json);
    }

    private static void execute(LargeOfflineWorkload workload, FeatureDagEngine engine, int index, String mode, String scope, boolean borrowed) {
        if (mode.equals("batch")) {
            sink = engine.generateBatch(scope.equals("request")
                    ? (borrowed ? OfflineBatchGenerateRequest.borrowed("measured", workload.rows(index))
                            : new OfflineBatchGenerateRequest("measured", workload.rows(index)))
                    : (borrowed ? workload.borrowedBatch(index) : workload.batch(index)));
        } else {
            for (int row = 0; row < workload.rows(index).size(); row++) {
                sink = engine.generate(scope.equals("request")
                        ? (borrowed ? OfflineGenerateRequest.borrowed("measured", workload.rows(index).get(row))
                                : new OfflineGenerateRequest("measured", workload.rows(index).get(row)))
                        : (borrowed ? workload.borrowedSingles(index).get(row) : workload.singles(index).get(row)));
            }
        }
    }

    static void verify(LargeOfflineWorkload workload, FeatureDagEngine engine, int index) {
        var actual = engine.generateBatch(workload.batch(index)).rows();
        if (!actual.equals(engine.generateBatch(workload.borrowedBatch(index)).rows())) throw new AssertionError("Borrowed batch mismatch");
        if (actual.size() != workload.rows(index).size()) throw new AssertionError("Row count mismatch");
        for (int row = 0; row < actual.size(); row++) {
            var expected = workload.expected(workload.rows(index).get(row));
            if (!expected.equals(actual.get(row))) throw new AssertionError("Batch oracle mismatch: " + index + "/" + row);
            if (!expected.equals(engine.generate(workload.singles(index).get(row)).featureValues())) {
                throw new AssertionError("Single oracle mismatch: " + index + "/" + row);
            }
            if (!expected.equals(engine.generate(workload.borrowedSingles(index).get(row)).featureValues())) {
                throw new AssertionError("Borrowed single oracle mismatch");
            }
        }
    }

    private static long[] gc() {
        long count = 0, time = 0;
        for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            count = count < 0 || bean.getCollectionCount() < 0 ? -1 : count + bean.getCollectionCount();
            time = time < 0 || bean.getCollectionTime() < 0 ? -1 : time + bean.getCollectionTime();
        }
        return new long[] {count, time};
    }

    private static int positive(String value) {
        int parsed = Integer.parseInt(value);
        if (parsed < 1) throw new IllegalArgumentException("Expected positive integer: " + value);
        return parsed;
    }

    private static String choice(String value, String first, String second) {
        if (!value.equals(first) && !value.equals(second)) throw new IllegalArgumentException("Expected " + first + " or " + second);
        return value;
    }
}
