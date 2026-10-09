package com.example.featuredag.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** JFR Java execution samples and allocation weights; not native/off-CPU profiling. */
public final class JfrFlamegraphExporter {
    private JfrFlamegraphExporter() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("recording.jfr outputDirectory");
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        Map<String, Long> cpu = new TreeMap<>();
        Map<String, Long> allocation = new TreeMap<>();
        Map<String, Long> inclusive = new TreeMap<>();
        Map<String, Long> leaves = new TreeMap<>();
        Map<String, Long> classes = new TreeMap<>();
        Map<String, Long> allocationSites = new TreeMap<>();
        Map<String, Long> events = new TreeMap<>();
        List<Double> pauses = new ArrayList<>();
        List<Double> stalls = new ArrayList<>();
        int truncated = 0;
        long retainedCpu = 0;
        long retainedAllocations = 0;
        long omittedFirstAllocationWeight = 0;
        int omittedFirstAllocationSamples = 0;
        // JDK 21 allocation weights can include allocations before a recording starts.
        // Find each thread's chronologically first event (JFR file order is not timestamp order).
        Map<Long, Instant> firstAllocation = new TreeMap<>();
        try (RecordingFile recording = new RecordingFile(Path.of(args[0]))) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                if (event.getEventType().getName().equals("jdk.ObjectAllocationSample")) {
                    firstAllocation.merge(event.getThread().getId(), event.getStartTime(),
                            (a, b) -> a.isBefore(b) ? a : b);
                }
            }
        }
        try (RecordingFile recording = new RecordingFile(Path.of(args[0]))) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String type = event.getEventType().getName();
                events.merge(type, 1L, Long::sum);
                if (type.equals("jdk.GCPhasePause")) pauses.add(event.getDuration().toNanos() / 1e6);
                if (type.equals("jdk.ZAllocationStall")) stalls.add(event.getDuration().toNanos() / 1e6);
                if (!type.equals("jdk.ExecutionSample") && !type.equals("jdk.ObjectAllocationSample")) continue;
                if (type.equals("jdk.ObjectAllocationSample")
                        && event.getStartTime().equals(firstAllocation.get(event.getThread().getId()))) {
                    omittedFirstAllocationWeight += event.getLong("weight");
                    omittedFirstAllocationSamples++;
                    continue;
                }
                RecordedStackTrace stack = event.getStackTrace();
                if (stack == null) continue;
                List<String> frames = new ArrayList<>();
                for (RecordedFrame frame : stack.getFrames()) {
                    frames.add(frame.getMethod().getType().getName() + "." + frame.getMethod().getName());
                }
                // Restrict both flame graphs to the timed API path, excluding JFR/JIT helper work.
                if (!frames.contains(LargeOnlineBenchmark.class.getName() + ".executeRequest")
                        && !frames.contains(LargeOfflineBenchmark.class.getName() + ".execute")) continue;
                if (stack.isTruncated()) truncated++;
                String leaf = frames.get(0);
                Collections.reverse(frames);
                String folded = String.join(";", frames);
                if (type.equals("jdk.ExecutionSample")) {
                    retainedCpu++;
                    cpu.merge(folded, 1L, Long::sum);
                    leaves.merge(leaf, 1L, Long::sum);
                    for (String frame : new java.util.HashSet<>(frames)) inclusive.merge(frame, 1L, Long::sum);
                } else {
                    retainedAllocations++;
                    long weight = event.getLong("weight");
                    allocation.merge(folded, weight, Long::sum);
                    classes.merge(event.getClass("objectClass").getName(), weight, Long::sum);
                    allocationSites.merge(leaf, weight, Long::sum);
                }
            }
        }
        writeFolded(output.resolve("cpu.folded"), cpu);
        writeFolded(output.resolve("allocation.folded"), allocation);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("note", "Java execution samples in online executeRequest or offline execute; allocation weights are estimates. Inclusive percentages overlap.");
        summary.put("cpu_samples", retainedCpu);
        summary.put("all_execution_samples", events.getOrDefault("jdk.ExecutionSample", 0L));
        summary.put("excluded_execution_samples", events.getOrDefault("jdk.ExecutionSample", 0L) - retainedCpu);
        summary.put("retained_allocation_samples", retainedAllocations);
        summary.put("allocation_weight_bytes", allocation.values().stream().mapToLong(Long::longValue).sum());
        summary.put("retained_truncated_stacks", truncated);
        summary.put("omitted_first_allocation_samples", omittedFirstAllocationSamples);
        summary.put("omitted_first_allocation_weight_bytes", omittedFirstAllocationWeight);
        summary.put("cpu_inclusive", sorted(inclusive));
        summary.put("cpu_leaf", sorted(leaves));
        summary.put("allocation_classes", sorted(classes));
        summary.put("allocation_sites", sorted(allocationSites));
        summary.put("gc_pause_ms", distribution(pauses));
        summary.put("z_allocation_stall_ms", distribution(stalls));
        summary.put("event_counts", events);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("profile-summary.json").toFile(), summary);
        if (cpu.isEmpty() || allocation.isEmpty()) throw new IllegalStateException("No request samples; increase recording duration");
        System.out.println("Exported " + retainedCpu + " execution samples; " + pauses.size() + " GC pauses");
    }

    private static Map<String, Long> sorted(Map<String, Long> values) {
        Map<String, Long> result = new LinkedHashMap<>();
        values.entrySet().stream().sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    private static Map<String, Object> distribution(List<Double> values) {
        Collections.sort(values);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", values.size());
        result.put("sum", values.stream().mapToDouble(Double::doubleValue).sum());
        result.put("p50", values.isEmpty() ? null : values.get((values.size() - 1) / 2));
        result.put("p99", values.isEmpty() ? null : values.get((int) Math.ceil(values.size() * .99) - 1));
        result.put("max", values.isEmpty() ? null : values.get(values.size() - 1));
        return result;
    }

    private static void writeFolded(Path file, Map<String, Long> values) throws Exception {
        StringBuilder text = new StringBuilder();
        values.forEach((stack, weight) -> text.append(stack).append(' ').append(weight).append('\n'));
        Files.writeString(file, text);
    }
}
