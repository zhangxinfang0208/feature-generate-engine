package com.example.featuredag.demo;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.GenerateResult;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OnlineBatchGenerateRequest;
import com.example.featuredag.api.OnlineBatchGenerateResult;
import com.example.featuredag.api.OnlineGenerateRequest;
import com.example.featuredag.api.OnlineRequestGroup;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.physical.PhysicalExecutorIds;
import com.example.featuredag.runtime.ExecutionDiagnostics;
import com.example.featuredag.runtime.InMemoryRuntimeObserver;
import com.example.featuredag.runtime.NodeExecutionSnapshot;
import com.example.featuredag.runtime.OperatorInvocationKind;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Public-API demo for deep expression CSE and Native Batch execution.
 *
 * <p>This demo intentionally keeps physical fusion disabled and reports timing without
 * asserting a machine-dependent speedup threshold.
 */
public final class NativeBatchPerformanceDemo {
    private static final String CONFIG_RESOURCE = "/demo/native-batch-performance.json";
    private static final String COMPLEX_DISTINCT = "complex_distinct";
    private static final String COMPLEX_DISTINCT_ALIAS = "complex_distinct_alias";
    private static final String DEEP_NUMERIC_BUCKET = "deep_numeric_bucket";
    private static final String DEEP_NUMERIC_BUCKET_ALIAS = "deep_numeric_bucket_alias";
    private static final int EXPECTED_NATIVE_OPERATOR_NODES = 10;

    private NativeBatchPerformanceDemo() {}

    public static void runSmokeTest() {
        run(new Options(64, 2, 16, 0, 1), false);
    }

    public static void main(String[] args) {
        run(Options.parse(args), true);
    }

    private static void run(Options options, boolean printReport) {
        String config = loadConfig();
        List<OnlineRequestGroup> groups = createGroups(options);

        InMemoryRuntimeObserver primaryObserver = new InMemoryRuntimeObserver(32);
        InMemoryRuntimeObserver aliasObserver = new InMemoryRuntimeObserver(4096);
        FeatureDagEngine primaryEngine = createEngine(
                config,
                "native-batch-primary",
                primaryObserver,
                targetFeatures(COMPLEX_DISTINCT, DEEP_NUMERIC_BUCKET));
        FeatureDagEngine aliasEngine = createEngine(
                config,
                "native-batch-alias",
                aliasObserver,
                targetFeatures(
                        COMPLEX_DISTINCT,
                        COMPLEX_DISTINCT_ALIAS,
                        DEEP_NUMERIC_BUCKET,
                        DEEP_NUMERIC_BUCKET_ALIAS));

        primaryEngine.generateBatch(new OnlineBatchGenerateRequest(
                "native-batch-primary-structure", groups));
        OnlineBatchGenerateResult aliasStructureResult = aliasEngine.generateBatch(
                new OnlineBatchGenerateRequest("native-batch-alias-structure", groups));
        assertStructure(primaryObserver.latest(), aliasObserver.latest());
        assertAliases(aliasStructureResult);

        for (int round = 0; round < options.warmupRounds; round++) {
            List<GenerateResult> individual = executeIndividual(
                    aliasEngine, groups, "native-batch-warmup-individual-" + round);
            OnlineBatchGenerateResult grouped = aliasEngine.generateBatch(
                    new OnlineBatchGenerateRequest(
                            "native-batch-warmup-grouped-" + round, groups));
            assertEquivalent(individual, grouped);
        }

        long[] individualDurations = new long[options.measurementRounds];
        long[] groupedDurations = new long[options.measurementRounds];
        for (int round = 0; round < options.measurementRounds; round++) {
            long individualStart = System.nanoTime();
            List<GenerateResult> individual = executeIndividual(
                    aliasEngine, groups, "native-batch-measure-individual-" + round);
            individualDurations[round] = System.nanoTime() - individualStart;

            long groupedStart = System.nanoTime();
            OnlineBatchGenerateResult grouped = aliasEngine.generateBatch(
                    new OnlineBatchGenerateRequest(
                            "native-batch-measure-grouped-" + round, groups));
            groupedDurations[round] = System.nanoTime() - groupedStart;
            assertEquivalent(individual, grouped);
            assertAliases(grouped);
        }

        if (printReport) {
            printReport(options, individualDurations, groupedDurations, aliasObserver.latest());
        }
    }

    private static FeatureDagEngine createEngine(
            String config,
            String planId,
            InMemoryRuntimeObserver observer,
            Set<String> targets) {
        return FeatureDagEngine.init(
                config,
                InitOptions.builder()
                        .environment(ExecutionEnvironment.ONLINE)
                        .planId(planId)
                        .targetFeatures(targets)
                        .runtimeObserver(observer)
                        .build());
    }

    private static Set<String> targetFeatures(String... names) {
        return new LinkedHashSet<String>(Arrays.asList(names));
    }

    private static List<OnlineRequestGroup> createGroups(Options options) {
        List<OnlineRequestGroup> groups = new ArrayList<OnlineRequestGroup>(
                options.groupCount);
        for (int groupIndex = 0; groupIndex < options.groupCount; groupIndex++) {
            Map<String, List<?>> shared = new LinkedHashMap<String, List<?>>();
            List<String> codes = new ArrayList<String>(options.sequenceLength);
            List<String> labels = new ArrayList<String>(options.sequenceLength);
            List<Integer> allIndices = new ArrayList<Integer>(options.sequenceLength);
            List<String> indexTags = new ArrayList<String>(options.sequenceLength);
            List<Double> numbers = new ArrayList<Double>(options.sequenceLength);
            for (int index = 0; index < options.sequenceLength; index++) {
                codes.add("code-" + ((index + groupIndex) % 128));
                labels.add("label-" + (index % 32));
                allIndices.add(index);
                indexTags.add("tag-" + (index % 64));
                numbers.add(index + groupIndex + 1.0);
            }
            shared.put("codes", immutable(codes));
            shared.put("labels", immutable(labels));
            shared.put("all_indices", immutable(allIndices));
            shared.put("index_tags", immutable(indexTags));
            shared.put("number_sequence", immutable(numbers));

            List<Map<String, List<?>>> candidates =
                    new ArrayList<Map<String, List<?>>>(options.candidatesPerGroup);
            for (int candidateIndex = 0;
                    candidateIndex < options.candidatesPerGroup;
                    candidateIndex++) {
                Map<String, List<?>> candidate = new LinkedHashMap<String, List<?>>();
                candidate.put(
                        "target_tag",
                        Collections.<String>singletonList(
                                "tag-" + (candidateIndex % 64)));
                candidate.put(
                        "delta_base",
                        Collections.<Double>singletonList(
                                (candidateIndex % 32) + 1.0));
                candidates.add(candidate);
            }
            groups.add(new OnlineRequestGroup(
                    "native-batch-group-" + groupIndex, shared, candidates));
        }
        return Collections.unmodifiableList(groups);
    }

    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }

    private static List<GenerateResult> executeIndividual(
            FeatureDagEngine engine,
            List<OnlineRequestGroup> groups,
            String executionPrefix) {
        List<GenerateResult> results = new ArrayList<GenerateResult>(groups.size());
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            OnlineRequestGroup group = groups.get(groupIndex);
            results.add(engine.generate(new OnlineGenerateRequest(
                    executionPrefix + "-group-" + groupIndex,
                    group.sharedValues(),
                    group.candidates())));
        }
        return results;
    }

    private static void assertEquivalent(
            List<GenerateResult> individual,
            OnlineBatchGenerateResult grouped) {
        require(individual.size() == grouped.groupResults().size(),
                "Individual/grouped result size mismatch");
        for (int groupIndex = 0; groupIndex < individual.size(); groupIndex++) {
            GenerateResult expected = individual.get(groupIndex);
            GenerateResult actual = grouped.groupResults().get(groupIndex);
            require(expected.featureValues().equals(actual.featureValues()),
                    "Request output mismatch for group " + groupIndex);
            require(expected.candidateFeatureValues().equals(
                            actual.candidateFeatureValues()),
                    "Candidate output mismatch for group " + groupIndex);
        }
    }

    private static void assertAliases(OnlineBatchGenerateResult result) {
        for (int groupIndex = 0;
                groupIndex < result.groupResults().size();
                groupIndex++) {
            List<Map<String, List<?>>> candidates =
                    result.groupResults().get(groupIndex).candidateFeatureValues();
            for (int candidateIndex = 0;
                    candidateIndex < candidates.size();
                    candidateIndex++) {
                Map<String, List<?>> values = candidates.get(candidateIndex);
                require(values.get(COMPLEX_DISTINCT).equals(
                                values.get(COMPLEX_DISTINCT_ALIAS)),
                        "Sequence alias mismatch for group " + groupIndex
                                + ", candidate " + candidateIndex);
                require(values.get(DEEP_NUMERIC_BUCKET).equals(
                                values.get(DEEP_NUMERIC_BUCKET_ALIAS)),
                        "Numeric alias mismatch for group " + groupIndex
                                + ", candidate " + candidateIndex);
            }
        }
    }

    private static void assertStructure(
            ExecutionDiagnostics primary,
            ExecutionDiagnostics alias) {
        require(alias.logicalNodeCount() == primary.logicalNodeCount() + 2,
                "Alias outputs should add only two logical output nodes: primary="
                        + primary.logicalNodeCount() + ", alias="
                        + alias.logicalNodeCount());
        require(alias.physicalNodeCount() == primary.physicalNodeCount() + 2,
                "Alias outputs should add only two physical output nodes: primary="
                        + primary.physicalNodeCount() + ", alias="
                        + alias.physicalNodeCount());
        require(alias.fusedPhysicalNodeCount() == 0,
                "Native Batch demo must not contain fused physical nodes");

        int genericOperatorNodes = 0;
        int nativeNodes = 0;
        int scalarAdapterNodes = 0;
        for (NodeExecutionSnapshot node : alias.nodes()) {
            if (!PhysicalExecutorIds.GENERIC_OPERATOR.equals(node.executorId())) continue;
            genericOperatorNodes++;
            // discrete/log_base/get_seq_length/slice_by_indices 不提供原生 Batch，
            // 由标量适配器逐行执行；count_distinct/zip_concat/find_indices/calc_delta_seq 走原生 Batch
            if (node.operatorInvocationKind() == OperatorInvocationKind.BATCH_NATIVE) {
                nativeNodes++;
            } else if (node.operatorInvocationKind()
                    == OperatorInvocationKind.BATCH_SCALAR_ADAPTER) {
                scalarAdapterNodes++;
            } else {
                throw new IllegalStateException(
                        "Unexpected invocation kind for " + node.physicalNodeId()
                                + ": " + node.operatorInvocationKind());
            }
        }
        require(genericOperatorNodes == EXPECTED_NATIVE_OPERATOR_NODES,
                "Expected " + EXPECTED_NATIVE_OPERATOR_NODES
                        + " generic operator nodes, got " + genericOperatorNodes);
        // CSE 合并后：complex_distinct 分支 3 native（count/zip/find）+ 3 scalar（slice×3）；
        // deep_numeric_bucket 分支 1 native（calc_delta_seq）+ 3 scalar（discrete/log_base/get_seq_length）
        require(nativeNodes == 4,
                "Expected 4 Native operator nodes, got " + nativeNodes);
        require(scalarAdapterNodes == 6,
                "Expected 6 scalar-adapter operator nodes, got " + scalarAdapterNodes);
    }

    private static void printReport(
            Options options,
            long[] individualDurations,
            long[] groupedDurations,
            ExecutionDiagnostics diagnostics) {
        long candidates = (long) options.groupCount * options.candidatesPerGroup;
        System.out.println("=== NATIVE BATCH PERFORMANCE DEMO ===");
        System.out.println("sequenceLength=" + options.sequenceLength
                + ", groups=" + options.groupCount
                + ", candidatesPerGroup=" + options.candidatesPerGroup
                + ", totalCandidates=" + candidates
                + ", warmups=" + options.warmupRounds
                + ", measurements=" + options.measurementRounds);
        printTiming("individual", individualDurations, candidates);
        printTiming("grouped", groupedDurations, candidates);
        System.out.println("diagnostics decodeMs=" + millis(diagnostics.decodeDurationNanos())
                + ", runtimeMs=" + millis(diagnostics.runtimeDurationNanos())
                + ", encodeMs=" + millis(diagnostics.encodeDurationNanos())
                + ", logicalNodes=" + diagnostics.logicalNodeCount()
                + ", physicalNodes=" + diagnostics.physicalNodeCount()
                + ", nativeNodes=" + countNativeNodes(diagnostics)
                + ", fusedNodes=" + diagnostics.fusedPhysicalNodeCount());
    }

    private static void printTiming(String name, long[] durations, long candidates) {
        long[] sorted = durations.clone();
        Arrays.sort(sorted);
        long minimum = sorted[0];
        long median = percentile(sorted, 0.50);
        long p95 = percentile(sorted, 0.95);
        double throughput = candidates * 1_000_000_000.0 / median;
        System.out.println(name + " minMs=" + millis(minimum)
                + ", medianMs=" + millis(median)
                + ", p95Ms=" + millis(p95)
                + ", candidatesPerSecond="
                + String.format(Locale.ROOT, "%.2f", throughput));
    }

    private static long percentile(long[] sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static int countNativeNodes(ExecutionDiagnostics diagnostics) {
        int result = 0;
        for (NodeExecutionSnapshot node : diagnostics.nodes()) {
            if (node.operatorInvocationKind() == OperatorInvocationKind.BATCH_NATIVE) {
                result++;
            }
        }
        return result;
    }

    private static String loadConfig() {
        InputStream stream = NativeBatchPerformanceDemo.class.getResourceAsStream(
                CONFIG_RESOURCE);
        if (stream == null) {
            throw new IllegalStateException(
                    "Missing demo resource: " + CONFIG_RESOURCE);
        }
        StringBuilder json = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                json.append(buffer, 0, count);
            }
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Failed to read demo resource: " + CONFIG_RESOURCE, error);
        }
        return json.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class Options {
        private static final int DEFAULT_SEQUENCE_LENGTH = 10_000;
        private static final int DEFAULT_GROUP_COUNT = 8;
        private static final int DEFAULT_CANDIDATES_PER_GROUP = 1_000;
        private static final int DEFAULT_WARMUP_ROUNDS = 2;
        private static final int DEFAULT_MEASUREMENT_ROUNDS = 5;

        private final int sequenceLength;
        private final int groupCount;
        private final int candidatesPerGroup;
        private final int warmupRounds;
        private final int measurementRounds;

        private Options(
                int sequenceLength,
                int groupCount,
                int candidatesPerGroup,
                int warmupRounds,
                int measurementRounds) {
            this.sequenceLength = positive(sequenceLength, "sequenceLength");
            this.groupCount = positive(groupCount, "groupCount");
            this.candidatesPerGroup = positive(
                    candidatesPerGroup, "candidatesPerGroup");
            this.warmupRounds = nonNegative(warmupRounds, "warmupRounds");
            this.measurementRounds = positive(
                    measurementRounds, "measurementRounds");
        }

        private static Options parse(String[] args) {
            if (args.length > 5) {
                throw new IllegalArgumentException(
                        "Usage: NativeBatchPerformanceDemo "
                                + "[sequenceLength] [groupCount] "
                                + "[candidatesPerGroup] [warmups] [measurements]");
            }
            return new Options(
                    argument(args, 0, DEFAULT_SEQUENCE_LENGTH, "sequenceLength"),
                    argument(args, 1, DEFAULT_GROUP_COUNT, "groupCount"),
                    argument(args, 2, DEFAULT_CANDIDATES_PER_GROUP,
                            "candidatesPerGroup"),
                    argument(args, 3, DEFAULT_WARMUP_ROUNDS, "warmupRounds"),
                    argument(args, 4, DEFAULT_MEASUREMENT_ROUNDS,
                            "measurementRounds"));
        }

        private static int argument(
                String[] args,
                int index,
                int defaultValue,
                String name) {
            if (index >= args.length) return defaultValue;
            try {
                return Integer.parseInt(args[index]);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(
                        name + " must be an integer: " + args[index], error);
            }
        }

        private static int positive(int value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }

        private static int nonNegative(int value, String name) {
            if (value < 0) {
                throw new IllegalArgumentException(
                        name + " must be non-negative");
            }
            return value;
        }
    }
}
