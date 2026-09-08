package com.example.featuredag.demo;

import com.example.featuredag.operator.BatchColumn;
import com.example.featuredag.operator.BatchDomain;
import com.example.featuredag.operator.BatchKernelKind;
import com.example.featuredag.operator.BatchLayout;
import com.example.featuredag.operator.BatchOperatorCall;
import com.example.featuredag.operator.BatchOperatorResult;
import com.example.featuredag.operator.ListBatchColumn;
import com.example.featuredag.operator.OperatorRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** find_indices_any 原生 Batch 与标量适配器的可复现微基准。 */
public final class FindIndicesAnyBatchPerformanceDemo {
    private static volatile long blackhole;

    private FindIndicesAnyBatchPerformanceDemo() {}

    public static void main(String[] args) {
        Options options = Options.parse(args);
        OperatorRegistry registry = OperatorRegistry.standard();
        if (registry.batchKernelKind("find_indices_any") != BatchKernelKind.NATIVE) {
            throw new IllegalStateException("find_indices_any must register a native Batch kernel");
        }

        System.out.println("scenario\tsequenceLength\trows\ttargetCount"
                + "\tscalarMedianMs\tnativeMedianMs\tspeedup");
        for (int sequenceLength : options.sequenceLengths) {
            runScenario(registry, options, sequenceLength, true);
            runScenario(registry, options, sequenceLength, false);
        }
        System.out.println("blackhole=" + blackhole);
    }

    private static void runScenario(
            OperatorRegistry registry,
            Options options,
            int sequenceLength,
            boolean sharedSource) {
        BatchOperatorCall call = createCall(options, sequenceLength, sharedSource);
        assertEquivalent(registry, call);

        for (int round = 0; round < options.warmupRounds; round++) {
            consume(evaluate(registry, call, BatchKernelKind.SCALAR_ADAPTER));
            consume(evaluate(registry, call, BatchKernelKind.NATIVE));
        }

        long[] scalarDurations = new long[options.measurementRounds];
        long[] nativeDurations = new long[options.measurementRounds];
        for (int round = 0; round < options.measurementRounds; round++) {
            if ((round & 1) == 0) {
                scalarDurations[round] = measure(registry, call, BatchKernelKind.SCALAR_ADAPTER);
                nativeDurations[round] = measure(registry, call, BatchKernelKind.NATIVE);
            } else {
                nativeDurations[round] = measure(registry, call, BatchKernelKind.NATIVE);
                scalarDurations[round] = measure(registry, call, BatchKernelKind.SCALAR_ADAPTER);
            }
        }

        long scalarMedian = median(scalarDurations);
        long nativeMedian = median(nativeDurations);
        System.out.println((sharedSource ? "shared" : "independent")
                + "\t" + sequenceLength
                + "\t" + call.rowCount()
                + "\t" + options.targetCount
                + "\t" + millis(scalarMedian)
                + "\t" + millis(nativeMedian)
                + "\t" + String.format(
                        Locale.ROOT, "%.2fx", (double) scalarMedian / nativeMedian));
    }

    private static long measure(
            OperatorRegistry registry,
            BatchOperatorCall call,
            BatchKernelKind kind) {
        long start = System.nanoTime();
        BatchOperatorResult result = evaluate(registry, call, kind);
        consume(result);
        return System.nanoTime() - start;
    }

    private static BatchOperatorResult evaluate(
            OperatorRegistry registry,
            BatchOperatorCall call,
            BatchKernelKind kind) {
        return registry.evaluateBatch("find_indices_any", call, kind);
    }

    private static void assertEquivalent(OperatorRegistry registry, BatchOperatorCall call) {
        BatchOperatorResult scalar = evaluate(registry, call, BatchKernelKind.SCALAR_ADAPTER);
        BatchOperatorResult nativeResult = evaluate(registry, call, BatchKernelKind.NATIVE);
        for (int rowIndex = 0; rowIndex < call.rowCount(); rowIndex++) {
            Object expected = scalar.values().valueAt(rowIndex);
            Object actual = nativeResult.values().valueAt(rowIndex);
            if (!expected.equals(actual)) {
                throw new AssertionError(
                        "result mismatch at row " + rowIndex
                                + ": expected=" + expected + ", actual=" + actual);
            }
        }
    }

    private static void consume(BatchOperatorResult result) {
        long checksum = 1L;
        for (int rowIndex = 0; rowIndex < result.values().size(); rowIndex++) {
            List<?> value = (List<?>) result.values().valueAt(rowIndex);
            checksum = checksum * 31L + value.size();
            if (!value.isEmpty()) {
                checksum = checksum * 31L + ((Number) value.get(0)).longValue();
                checksum = checksum * 31L
                        + ((Number) value.get(value.size() - 1)).longValue();
            }
        }
        blackhole ^= checksum;
    }

    private static BatchOperatorCall createCall(
            Options options,
            int sequenceLength,
            boolean sharedSource) {
        CandidateLayout layout = new CandidateLayout(
                options.groupCount, options.candidatesPerGroup);
        List<Object> sources = new ArrayList<Object>(layout.rowCount());
        List<Object> targets = new ArrayList<Object>(layout.rowCount());
        List<Object> sharedByGroup = new ArrayList<Object>(options.groupCount);
        List<Object> valuePool = valuePool(options.valueDomain);
        if (sharedSource) {
            for (int groupIndex = 0; groupIndex < options.groupCount; groupIndex++) {
                sharedByGroup.add(source(sequenceLength, valuePool, groupIndex));
            }
        }

        for (int rowIndex = 0; rowIndex < layout.rowCount(); rowIndex++) {
            int groupIndex = layout.groupIndexAt(rowIndex);
            sources.add(sharedSource
                    ? sharedByGroup.get(groupIndex)
                    : source(sequenceLength, valuePool, rowIndex));
            targets.add(targets(
                    options.targetCount,
                    valuePool,
                    groupIndex,
                    layout.indexInGroupAt(rowIndex)));
        }
        return new BatchOperatorCall(
                layout,
                Arrays.<BatchColumn>asList(
                        new ListBatchColumn(sources),
                        new ListBatchColumn(targets)));
    }

    private static List<Object> valuePool(int valueDomain) {
        List<Object> result = new ArrayList<Object>(valueDomain);
        for (int index = 0; index < valueDomain; index++) {
            result.add("v" + index);
        }
        return Collections.unmodifiableList(result);
    }

    private static List<Object> source(int length, List<Object> valuePool, int seed) {
        List<Object> result = new ArrayList<Object>(length);
        for (int index = 0; index < length; index++) {
            result.add(valuePool.get(
                    Math.floorMod(index * 31 + seed * 17, valuePool.size())));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<Object> targets(
            int targetCount,
            List<Object> valuePool,
            int groupIndex,
            int candidateIndex) {
        List<Object> result = new ArrayList<Object>(targetCount);
        for (int index = 0; index < targetCount; index++) {
            result.add(valuePool.get(Math.floorMod(
                    candidateIndex * 7 + groupIndex * 13 + index * 11,
                    valuePool.size())));
        }
        return Collections.unmodifiableList(result);
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static final class CandidateLayout implements BatchLayout {
        private final int rowCount;
        private final int candidatesPerGroup;

        private CandidateLayout(int groupCount, int candidatesPerGroup) {
            this.rowCount = groupCount * candidatesPerGroup;
            this.candidatesPerGroup = candidatesPerGroup;
        }

        @Override
        public BatchDomain domain() {
            return BatchDomain.ONLINE_CANDIDATE;
        }

        @Override
        public int rowCount() {
            return rowCount;
        }

        @Override
        public int groupIndexAt(int rowIndex) {
            return rowIndex / candidatesPerGroup;
        }

        @Override
        public int indexInGroupAt(int rowIndex) {
            return rowIndex % candidatesPerGroup;
        }
    }

    private static final class Options {
        private static final int[] DEFAULT_SEQUENCE_LENGTHS = {50, 200, 1000, 3000};
        private static final int DEFAULT_GROUP_COUNT = 4;
        private static final int DEFAULT_CANDIDATES_PER_GROUP = 500;
        private static final int DEFAULT_TARGET_COUNT = 5;
        private static final int DEFAULT_VALUE_DOMAIN = 64;
        private static final int DEFAULT_WARMUP_ROUNDS = 5;
        private static final int DEFAULT_MEASUREMENT_ROUNDS = 9;

        private final int[] sequenceLengths;
        private final int groupCount;
        private final int candidatesPerGroup;
        private final int targetCount;
        private final int valueDomain;
        private final int warmupRounds;
        private final int measurementRounds;

        private Options(
                int[] sequenceLengths,
                int groupCount,
                int candidatesPerGroup,
                int targetCount,
                int valueDomain,
                int warmupRounds,
                int measurementRounds) {
            this.sequenceLengths = sequenceLengths;
            this.groupCount = positive(groupCount, "groupCount");
            this.candidatesPerGroup = positive(candidatesPerGroup, "candidatesPerGroup");
            this.targetCount = positive(targetCount, "targetCount");
            this.valueDomain = positive(valueDomain, "valueDomain");
            this.warmupRounds = nonNegative(warmupRounds, "warmupRounds");
            this.measurementRounds = positive(measurementRounds, "measurementRounds");
        }

        private static Options parse(String[] args) {
            if (args.length > 7) {
                throw new IllegalArgumentException(
                        "Usage: FindIndicesAnyBatchPerformanceDemo "
                                + "[sequenceLengthsCsv] [groupCount] [candidatesPerGroup] "
                                + "[targetCount] [valueDomain] [warmups] [measurements]");
            }
            return new Options(
                    args.length >= 1 ? lengths(args[0]) : DEFAULT_SEQUENCE_LENGTHS.clone(),
                    argument(args, 1, DEFAULT_GROUP_COUNT, "groupCount"),
                    argument(args, 2, DEFAULT_CANDIDATES_PER_GROUP, "candidatesPerGroup"),
                    argument(args, 3, DEFAULT_TARGET_COUNT, "targetCount"),
                    argument(args, 4, DEFAULT_VALUE_DOMAIN, "valueDomain"),
                    argument(args, 5, DEFAULT_WARMUP_ROUNDS, "warmups"),
                    argument(args, 6, DEFAULT_MEASUREMENT_ROUNDS, "measurements"));
        }

        private static int[] lengths(String raw) {
            String[] parts = raw.split(",");
            int[] result = new int[parts.length];
            for (int index = 0; index < parts.length; index++) {
                result[index] = positive(
                        Integer.parseInt(parts[index].trim()), "sequenceLength");
            }
            return result;
        }

        private static int argument(String[] args, int index, int fallback, String name) {
            return args.length > index ? positive(Integer.parseInt(args[index]), name) : fallback;
        }

        private static int positive(int value, String name) {
            if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
            return value;
        }

        private static int nonNegative(int value, String name) {
            if (value < 0) throw new IllegalArgumentException(name + " must be non-negative");
            return value;
        }
    }
}
