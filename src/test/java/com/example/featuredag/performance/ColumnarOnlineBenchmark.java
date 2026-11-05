package com.example.featuredag.performance;

import com.example.featuredag.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;

/** Standalone comparative benchmark. No timing assertions in unit tests. */
public final class ColumnarOnlineBenchmark {
    private static volatile Object sink;
    @jdk.jfr.Name("featuredag.Request")
    @jdk.jfr.StackTrace(false)
    public static final class RequestEvent extends jdk.jfr.Event {
        public int inputIndex;
    }
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if (!Set.of("row-snapshot", "row-borrowed", "column-borrowed", "column-result").contains(mode))
            throw new IllegalArgumentException("Unknown mode: " + mode);
        int warmupSeconds = Integer.parseInt(args[1]);
        int measureSeconds = Integer.parseInt(args[2]);
        var workload = new LargeOnlineWorkload(512, 300, 8);
        var engine = workload.engine(false);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 300; i++) ids.add("item_" + i);
        List<Map<String,List<?>>> columns = new ArrayList<>();
        for (int r = 0; r < 8; r++) {
            Map<String,List<?>> cols = new LinkedHashMap<>();
            for (String name : engine.requiredCandidateInputNames()) {
                List<Object> values = new ArrayList<>();
                for (var row : workload.request(r).candidates()) values.add(row.get(name).getFirst());
                cols.put(name, values);
            }
            columns.add(cols);
            var a = engine.generate(workload.request(r));
            var b = engine.generateColumnar(OnlineColumnarGenerateRequest.borrowed("check", workload.request(r).sharedValues(), ids, cols));
            if (!a.featureValues().equals(b.featureValues()) || !a.candidateFeatureValues().equals(b.candidateFeatureValues()))
                throw new AssertionError("Row/column mismatch");
        }
        Runnable[] calls = new Runnable[8];
        for (int i = 0; i < 8; i++) {
            final int index = i;
            var raw = workload.request(i);
            calls[i] = () -> {
                if (mode.equals("column-result")) sink = engine.generateColumnarResult(
                        OnlineColumnarGenerateRequest.borrowed("bench", raw.sharedValues(), ids, columns.get(index)));
                else if (mode.equals("column-borrowed")) sink = engine.generateColumnar(
                        OnlineColumnarGenerateRequest.borrowed("bench", raw.sharedValues(), ids, columns.get(index)));
                else sink = engine.generate(mode.equals("row-borrowed")
                        ? OnlineGenerateRequest.borrowed("bench", raw.sharedValues(), raw.candidates())
                        : new OnlineGenerateRequest("bench", raw.sharedValues(), raw.candidates()));
            };
        }
        jdk.jfr.Recording recording = null;
        if (args.length > 4) {
            recording = new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));
            recording.disable("jdk.InitialEnvironmentVariable");
            recording.disable("jdk.InitialSystemProperty");
            recording.enable("jdk.Compilation").withThreshold(java.time.Duration.ZERO);
            recording.enable("jdk.CompilerPhase").withThreshold(java.time.Duration.ZERO);
            recording.enable("jdk.GCPhasePause").withThreshold(java.time.Duration.ZERO);
            recording.enable(RequestEvent.class).withThreshold(java.time.Duration.ZERO);
            recording.start();
        }
        int index = 0;
        long end = System.nanoTime() + warmupSeconds * 1_000_000_000L;
        while (System.nanoTime() < end) calls[index++ & 7].run();
        var allocation = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        allocation.setThreadAllocatedMemoryEnabled(true);
        long tid = Thread.currentThread().threadId();
        long[] durations = new long[1_000_000];
        int count = 0;
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long cpuBefore = os.getProcessCpuTime();
        long before = allocation.getThreadAllocatedBytes(tid);
        long start = System.nanoTime();
        end = start + measureSeconds * 1_000_000_000L;
        do {
            RequestEvent event = recording == null ? null : new RequestEvent();
            int input = index++ & 7;
            if (event != null) { event.inputIndex = input; event.begin(); }
            long t = System.nanoTime();
            calls[input].run();
            durations[count++] = System.nanoTime() - t;
            if (event != null) { event.end(); event.commit(); }
        } while (System.nanoTime() < end && count < durations.length);
        long elapsed = System.nanoTime() - start;
        long allocated = allocation.getThreadAllocatedBytes(tid) - before;
        long cpuNanos = os.getProcessCpuTime() - cpuBefore;
        if (recording != null) {
            recording.stop();
            recording.dump(Path.of(args[4]));
            recording.close();
        }
        Arrays.sort(durations, 0, count);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("process_cpu_ms_per_request", cpuNanos / 1e6 / count);
        result.put("profiled", args.length > 4);
        result.put("mode", mode); result.put("requests", count);
        result.put("qps", count * 1e9 / elapsed);
        for (double p : new double[]{0.5, 0.95, 0.99})
            result.put("p" + (int)(p*100) + "_ms", durations[(int)Math.ceil(count*p)-1]/1e6);
        result.put("allocated_bytes_per_request", allocated/(double)count);
        result.put("warmup_seconds", warmupSeconds); result.put("measure_seconds", measureSeconds);
        result.put("java", System.getProperty("java.runtime.version"));
        result.put("jvm_args", ManagementFactory.getRuntimeMXBean().getInputArguments());
        result.put("boundary", "request construction + generate; prebuilt typed inputs; excludes business mapping, splitting and writeback");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[3]).toFile(), result);
        System.out.println(result);
    }
}
