package com.example.featuredag.performance;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.GenerateResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit standalone benchmark; never performs timing assertions in Surefire. */
public final class LargeOnlineBenchmark {
    private static volatile GenerateResult sink;

    private LargeOnlineBenchmark() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 7) throw new IllegalArgumentException(
                "outputDir warmupSeconds measureSeconds sequenceLength candidates observe profile");
        Path output = Path.of(args[0]);
        int warmup = positive(args[1]);
        int seconds = positive(args[2]);
        int length = positive(args[3]);
        int candidates = positive(args[4]);
        boolean observe = bool(args[5]);
        boolean profile = bool(args[6]);
        Files.createDirectories(output);
        LargeOnlineWorkload workload = new LargeOnlineWorkload(length, candidates, 8);
        Files.writeString(output.resolve("features.json"), workload.config());
        FeatureDagEngine engine;
        long initNanos;
        try (Recording recording = recording(profile)) {
            long start = System.nanoTime();
            engine = workload.engine(observe);
            initNanos = System.nanoTime() - start;
            finish(recording, output.resolve("init.jfr"));
        }
        Files.writeString(output.resolve("logical-dag.txt"), engine.describeLogicalDag());
        Files.writeString(output.resolve("physical-plan.txt"), engine.describePhysicalPlan());
        // Independent oracle checks outside the measured region, including the full 500-row batch.
        verify(workload, engine, 0, candidates);
        System.out.println("Warmup: " + warmup + "s; observe=" + observe + "; profile=" + profile);
        long deadline = System.nanoTime() + Duration.ofSeconds(warmup).toNanos();
        int requestIndex = 0;
        while (System.nanoTime() < deadline) executeRequest(engine, workload, requestIndex++);
        List<Long> durations = new ArrayList<Long>();
        com.sun.management.ThreadMXBean allocation = allocationBean();
        long threadId = Thread.currentThread().threadId();
        long allocated;
        long elapsed;
        java.lang.management.ThreadMXBean threadCpu = ManagementFactory.getThreadMXBean();
        if (threadCpu.isCurrentThreadCpuTimeSupported() && !threadCpu.isThreadCpuTimeEnabled()) {
            threadCpu.setThreadCpuTimeEnabled(true);
        }
        com.sun.management.OperatingSystemMXBean processCpu = ManagementFactory.getOperatingSystemMXBean()
                instanceof com.sun.management.OperatingSystemMXBean bean ? bean : null;
        long callerCpuNanos;
        long processCpuNanos;
        long startUptimeMillis;
        long endUptimeMillis;
        Map<String, long[]> gcBefore;
        Map<String, long[]> gcAfter;
        System.out.println("Measurement: " + seconds + "s");
        try (Recording recording = recording(profile)) {
            gcBefore = gcCounters();
            long callerCpuBefore = threadCpu.isCurrentThreadCpuTimeSupported() ? threadCpu.getCurrentThreadCpuTime() : -1;
            long processCpuBefore = processCpu == null ? -1 : processCpu.getProcessCpuTime();
            startUptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
            long bytesBefore = allocation == null ? 0 : allocation.getThreadAllocatedBytes(threadId);
            long start = System.nanoTime();
            deadline = start + Duration.ofSeconds(seconds).toNanos();
            do {
                long callStart = System.nanoTime();
                executeRequest(engine, workload, requestIndex++);
                durations.add(System.nanoTime() - callStart);
            } while (System.nanoTime() < deadline);
            elapsed = System.nanoTime() - start;
            allocated = allocation == null ? -1 : allocation.getThreadAllocatedBytes(threadId) - bytesBefore;
            endUptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
            long callerCpuAfter = threadCpu.isCurrentThreadCpuTimeSupported() ? threadCpu.getCurrentThreadCpuTime() : -1;
            long processCpuAfter = processCpu == null ? -1 : processCpu.getProcessCpuTime();
            callerCpuNanos = callerCpuBefore < 0 || callerCpuAfter < 0 ? -1 : callerCpuAfter - callerCpuBefore;
            processCpuNanos = processCpuBefore < 0 || processCpuAfter < 0 ? -1 : processCpuAfter - processCpuBefore;
            gcAfter = gcCounters();
            finish(recording, output.resolve("measurement.jfr"));
        }
        verify(workload, engine, 7, candidates);
        StringBuilder csv = new StringBuilder("request,latency_ms\n");
        for (int i = 0; i < durations.size(); i++) csv.append(i).append(',').append(durations.get(i) / 1e6).append('\n');
        Files.writeString(output.resolve("latencies.csv"), csv);
        Collections.sort(durations);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("java", System.getProperty("java.runtime.version"));
        result.put("os", System.getProperty("os.name"));
        result.put("processors", Runtime.getRuntime().availableProcessors());
        result.put("jvm_args", ManagementFactory.getRuntimeMXBean().getInputArguments());
        result.put("gc", ManagementFactory.getGarbageCollectorMXBeans().stream().map(b -> b.getName()).toList());
        result.put("max_heap_bytes", Runtime.getRuntime().maxMemory());
        result.put("base", 320); result.put("sequence_base", 160); result.put("derived", 320);
        result.put("sequence_length", length); result.put("candidates", candidates);
        result.put("request_pool", 8); result.put("concurrency", 1);
        result.put("observe", observe); result.put("profile", profile);
        result.put("observer", observe ? "latest-diagnostics-volatile-sink" : "NOOP");
        if (observe) {
            var diagnostic = workload.latestDiagnostics();
            Map<String, Object> phases = new LinkedHashMap<>();
            phases.put("note", "One post-measurement validation request; not percentile statistics");
            phases.put("decode_ms", diagnostic.decodeDurationNanos() / 1e6);
            phases.put("runtime_ms", diagnostic.runtimeDurationNanos() / 1e6);
            phases.put("encode_ms", diagnostic.encodeDurationNanos() / 1e6);
            phases.put("physical_nodes", diagnostic.physicalNodeCount());
            phases.put("logical_nodes", diagnostic.logicalNodeCount());
            phases.put("fused_nodes", diagnostic.fusedPhysicalNodeCount());
            Map<String, Long> routes = new LinkedHashMap<>();
            for (var node : diagnostic.nodes()) {
                if (node.operatorInvocationKind() != null) routes.merge(node.operatorInvocationKind().name(), 1L, Long::sum);
            }
            phases.put("operator_routes", routes);
            result.put("last_validation_diagnostics", phases);
        }
        result.put("warmup_seconds", warmup); result.put("elapsed_seconds", elapsed / 1e9);
        result.put("measurement_start_uptime_ms", startUptimeMillis);
        result.put("measurement_end_uptime_ms", endUptimeMillis);
        result.put("caller_cpu_ms_per_request", callerCpuNanos < 0 ? -1 : callerCpuNanos / 1e6 / durations.size());
        result.put("caller_cpu_fraction", callerCpuNanos < 0 ? -1 : (double) callerCpuNanos / elapsed);
        result.put("process_cpu_ms_per_request", processCpuNanos < 0 ? -1 : processCpuNanos / 1e6 / durations.size());
        result.put("process_cpu_equivalent_cores", processCpuNanos < 0 ? -1 : (double) processCpuNanos / elapsed);
        Map<String, Object> gcDelta = new LinkedHashMap<>();
        gcAfter.forEach((name, after) -> {
            long[] before = gcBefore.get(name);
            if (before != null) {
                Map<String, Long> delta = new LinkedHashMap<>();
                delta.put("count", before[0] < 0 || after[0] < 0 ? -1 : after[0] - before[0]);
                delta.put("collection_time_ms", before[1] < 0 || after[1] < 0 ? -1 : after[1] - before[1]);
                gcDelta.put(name, delta);
            }
        });
        result.put("gc_counter_deltas", gcDelta);
        result.put("init_ms", initNanos / 1e6); result.put("requests", durations.size());
        result.put("request_qps", durations.size() * 1e9 / elapsed);
        result.put("candidate_rows_per_second", durations.size() * (double) candidates * 1e9 / elapsed);
        result.put("mean_ms", durations.stream().mapToLong(Long::longValue).average().orElseThrow() / 1e6);
        result.put("p50_ms", percentile(durations, 0.50)); result.put("p95_ms", percentile(durations, 0.95));
        result.put("p99_ms", percentile(durations, 0.99)); result.put("max_ms", percentile(durations, 1.0));
        result.put("allocated_bytes_per_request", allocated < 0 ? -1 : (double) allocated / durations.size());
        result.put("allocation_mib_per_second", allocated < 0 ? -1 : allocated / 1048576.0 / (elapsed / 1e9));
        String json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result);
        Files.writeString(output.resolve("summary.json"), json);
        System.out.println(json);
    }

    private static void executeRequest(FeatureDagEngine engine, LargeOnlineWorkload workload, int index) {
        sink = engine.generate(workload.request(index));
    }

    private static void verify(LargeOnlineWorkload workload, FeatureDagEngine engine, int index, int candidates) {
        GenerateResult result = engine.generate(workload.request(index));
        if (result.candidateFeatureValues().size() != candidates) throw new AssertionError("candidate count");
        for (int row = 0; row < candidates; row++) {
            if (!workload.expected(index, row).equals(result.candidateFeatureValues().get(row))) {
                throw new AssertionError("Oracle mismatch at request " + index + ", row " + row);
            }
        }
    }

    private static Recording recording(boolean enabled) throws Exception {
        if (!enabled) return null;
        Recording recording = new Recording(Configuration.getConfiguration("profile"));
        recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
        recording.enable("jdk.ObjectAllocationSample").with("throttle", "150/s").withStackTrace();
        recording.setMaxSize(256L * 1024 * 1024);
        recording.start();
        return recording;
    }

    private static void finish(Recording recording, Path file) throws Exception {
        if (recording != null) { recording.stop(); recording.dump(file); }
    }

    private static com.sun.management.ThreadMXBean allocationBean() {
        var bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean allocation)
                || !allocation.isThreadAllocatedMemorySupported()) return null;
        if (!allocation.isThreadAllocatedMemoryEnabled()) allocation.setThreadAllocatedMemoryEnabled(true);
        return allocation;
    }

    private static Map<String, long[]> gcCounters() {
        Map<String, long[]> counters = new LinkedHashMap<>();
        for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            counters.put(collector.getName(), new long[] {collector.getCollectionCount(), collector.getCollectionTime()});
        }
        return counters;
    }

    private static double percentile(List<Long> sorted, double fraction) {
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * fraction) - 1)) / 1e6;
    }

    private static int positive(String value) {
        int parsed = Integer.parseInt(value);
        if (parsed < 1) throw new IllegalArgumentException("Expected positive integer: " + value);
        return parsed;
    }

    private static boolean bool(String value) {
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Expected true or false");
        return Boolean.parseBoolean(value);
    }
}
