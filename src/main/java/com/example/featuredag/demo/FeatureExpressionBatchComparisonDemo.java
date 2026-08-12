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
 * 特征表达式层对比 demo：首期 8 个算子各以一个 DERIVED 特征表达式定义，
 * 走完整引擎链路（表达式解析 → 逻辑 DAG → 物理计划 → runtime 分派），
 * 对比「逐个请求 generate（不走批量请求）」与「generateBatch 批量请求」的执行差异。
 *
 * <p>引擎分派由输入载体决定（C10）：候选行存在时算子节点一律走原生 Batch
 * kernel（BATCH_NATIVE），差异集中在请求解码与调度摊销；本 demo 同时断言
 * 全部算子节点均为 BATCH_NATIVE、无物理融合节点。
 */
public final class FeatureExpressionBatchComparisonDemo {
    private static final String CONFIG_RESOURCE =
            "/demo/feature-expression-batch-comparison.json";
    private static final String[] TARGET_FEATURES = {
        "bucket_level", "log_amount", "code_window", "target_positions",
        "behavior_length", "distinct_codes", "joined_window", "delta_sequence",
    };
    /** 8 个特征表达式对应的逻辑算子节点数：1+1+1+1+1+1+3+1。 */
    private static final int EXPECTED_OPERATOR_NODES = 10;

    private FeatureExpressionBatchComparisonDemo() {}

    public static void runSmokeTest() {
        run(new Options(16, 2, 4, 1, 1), false);
    }

    public static void main(String[] args) {
        run(Options.parse(args), true);
    }

    private static void run(Options options, boolean printReport) {
        String config = loadConfig();
        List<OnlineRequestGroup> groups = createGroups(options);

        InMemoryRuntimeObserver observer = new InMemoryRuntimeObserver(32);
        FeatureDagEngine engine = FeatureDagEngine.init(
                config,
                InitOptions.builder()
                        .environment(ExecutionEnvironment.ONLINE)
                        .planId("feature-expression-batch-comparison")
                        .targetFeatures(targetFeatures())
                        .runtimeObserver(observer)
                        .build());

        long[] individualDurations = new long[options.measurementRounds];
        long[] groupedDurations = new long[options.measurementRounds];
        ExecutionDiagnostics individualDiagnostics = null;
        ExecutionDiagnostics groupedDiagnostics = null;
        for (int round = 0; round < options.warmupRounds; round++) {
            List<GenerateResult> individual = executeIndividual(
                    engine, groups, "feature-expression-warmup-individual-" + round);
            if (round == 0) individualDiagnostics = observer.latest();
            OnlineBatchGenerateResult grouped = engine.generateBatch(
                    new OnlineBatchGenerateRequest(
                            "feature-expression-warmup-grouped-" + round, groups));
            if (round == 0) groupedDiagnostics = observer.latest();
            assertEquivalent(individual, grouped);
        }
        for (int round = 0; round < options.measurementRounds; round++) {
            long individualStart = System.nanoTime();
            List<GenerateResult> individual = executeIndividual(
                    engine, groups, "feature-expression-measure-individual-" + round);
            individualDurations[round] = System.nanoTime() - individualStart;

            long groupedStart = System.nanoTime();
            OnlineBatchGenerateResult grouped = engine.generateBatch(
                    new OnlineBatchGenerateRequest(
                            "feature-expression-measure-grouped-" + round, groups));
            groupedDurations[round] = System.nanoTime() - groupedStart;
            assertEquivalent(individual, grouped);
        }

        assertInvocationMix(individualDiagnostics, groupedDiagnostics);
        if (printReport) {
            printReport(options, individualDurations, groupedDurations, groupedDiagnostics);
        }
    }

    private static Set<String> targetFeatures() {
        return new LinkedHashSet<String>(Arrays.asList(TARGET_FEATURES));
    }

    private static List<OnlineRequestGroup> createGroups(Options options) {
        List<OnlineRequestGroup> groups = new ArrayList<OnlineRequestGroup>(
                options.groupCount);
        for (int groupIndex = 0; groupIndex < options.groupCount; groupIndex++) {
            Map<String, List<?>> shared = new LinkedHashMap<String, List<?>>();
            List<String> codes = new ArrayList<String>(options.sequenceLength);
            List<Double> numbers = new ArrayList<Double>(options.sequenceLength);
            for (int index = 0; index < options.sequenceLength; index++) {
                codes.add("code-" + ((index + groupIndex) % 128));
                numbers.add(index + groupIndex + 1.0);
            }
            shared.put("codes", immutable(codes));
            shared.put("numbers", immutable(numbers));

            List<Map<String, List<?>>> candidates =
                    new ArrayList<Map<String, List<?>>>(options.candidatesPerGroup);
            for (int candidateIndex = 0;
                    candidateIndex < options.candidatesPerGroup;
                    candidateIndex++) {
                Map<String, List<?>> candidate = new LinkedHashMap<String, List<?>>();
                // log_base 要求 value > 0，discrete 边界 [0,10,50,100,500]
                candidate.put("amount", Collections.<Double>singletonList(
                        (candidateIndex % 32) + 1.0));
                // calc_delta_seq 的 base 不得为 1
                candidate.put("delta_base", Collections.<Double>singletonList(
                        (candidateIndex % 15) + 2.0));
                candidate.put("target_tag", Collections.<String>singletonList(
                        "code-" + ((candidateIndex * 3 + groupIndex) % 128)));
                candidates.add(candidate);
            }
            groups.add(new OnlineRequestGroup(
                    "feature-expression-group-" + groupIndex, shared, candidates));
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
        if (individual.size() != grouped.groupResults().size()) {
            throw new IllegalStateException(
                    "Individual/grouped result size mismatch: "
                            + individual.size() + " vs " + grouped.groupResults().size());
        }
        for (int groupIndex = 0; groupIndex < individual.size(); groupIndex++) {
            GenerateResult expected = individual.get(groupIndex);
            GenerateResult actual = grouped.groupResults().get(groupIndex);
            if (!expected.featureValues().equals(actual.featureValues())) {
                throw new IllegalStateException(
                        "Request output mismatch for group " + groupIndex);
            }
            if (!expected.candidateFeatureValues().equals(
                            actual.candidateFeatureValues())) {
                throw new IllegalStateException(
                        "Candidate output mismatch for group " + groupIndex);
            }
        }
    }

    /**
     * 表达式层载体分派断言（C10）：individual 单请求执行时，纯共享序列特征
     * （无候选输入）走 SINGLE kernel；候选特征按注册能力路由——保留原生 Batch 的
     * find_indices/count_distinct/zip_concat/calc_delta_seq 走 BATCH_NATIVE，
     * 不提供原生 Batch 的 discrete/log_base/slice_by_indices/get_seq_length
     * 走 BATCH_SCALAR_ADAPTER（逐行 single 语义）。grouped 批量执行时共享值
     * 向量化为请求批域，算子节点全部走批路径、无 SINGLE。
     */
    private static void assertInvocationMix(
            ExecutionDiagnostics individual,
            ExecutionDiagnostics grouped) {
        requireNoFusion(individual);
        requireNoFusion(grouped);
        int individualNative = countNodes(individual, OperatorInvocationKind.BATCH_NATIVE);
        int individualScalar = countNodes(
                individual, OperatorInvocationKind.BATCH_SCALAR_ADAPTER);
        int individualSingle = countNodes(individual, OperatorInvocationKind.SINGLE);
        if (individualNative < 1 || individualScalar < 1 || individualSingle < 1) {
            throw new IllegalStateException(
                    "Individual execution must mix Native/Scalar/Single invocations: "
                            + "native=" + individualNative + ", scalar=" + individualScalar
                            + ", single=" + individualSingle);
        }
        int groupedNative = countNodes(grouped, OperatorInvocationKind.BATCH_NATIVE);
        int groupedScalar = countNodes(grouped, OperatorInvocationKind.BATCH_SCALAR_ADAPTER);
        int groupedSingle = countNodes(grouped, OperatorInvocationKind.SINGLE);
        if (groupedNative < 1 || groupedScalar < 1 || groupedSingle != 0) {
            throw new IllegalStateException(
                    "Grouped execution must use batch paths for all operator nodes: "
                            + "native=" + groupedNative + ", scalar=" + groupedScalar
                            + ", single=" + groupedSingle);
        }
    }

    private static void requireNoFusion(ExecutionDiagnostics diagnostics) {
        if (diagnostics.fusedPhysicalNodeCount() != 0) {
            throw new IllegalStateException(
                    "Expression demo must not contain fused physical nodes");
        }
        int genericOperatorNodes = countNodes(
                diagnostics, OperatorInvocationKind.BATCH_NATIVE)
                + countNodes(diagnostics, OperatorInvocationKind.BATCH_SCALAR_ADAPTER)
                + countNodes(diagnostics, OperatorInvocationKind.SINGLE);
        if (genericOperatorNodes != EXPECTED_OPERATOR_NODES) {
            throw new IllegalStateException(
                    "Expected " + EXPECTED_OPERATOR_NODES
                            + " generic operator nodes, got " + genericOperatorNodes);
        }
    }

    private static void printReport(
            Options options,
            long[] individualDurations,
            long[] groupedDurations,
            ExecutionDiagnostics diagnostics) {
        long candidates = (long) options.groupCount * options.candidatesPerGroup;
        System.out.println("=== FEATURE EXPRESSION BATCH VS INDIVIDUAL ===");
        System.out.println("sequenceLength=" + options.sequenceLength
                + ", groups=" + options.groupCount
                + ", candidatesPerGroup=" + options.candidatesPerGroup
                + ", totalCandidates=" + candidates
                + ", warmups=" + options.warmupRounds
                + ", measurements=" + options.measurementRounds);
        printTiming("individual(逐请求 generate)", individualDurations, candidates);
        printTiming("grouped(generateBatch)", groupedDurations, candidates);
        long individualMedian = percentile(sorted(individualDurations), 0.50);
        long groupedMedian = percentile(sorted(groupedDurations), 0.50);
        System.out.println("grouped/individual 加速比="
                + String.format(Locale.ROOT, "%.2fx",
                        (double) individualMedian / groupedMedian));
        System.out.println("diagnostics decodeMs="
                + millis(diagnostics.decodeDurationNanos())
                + ", runtimeMs=" + millis(diagnostics.runtimeDurationNanos())
                + ", encodeMs=" + millis(diagnostics.encodeDurationNanos())
                + ", logicalNodes=" + diagnostics.logicalNodeCount()
                + ", physicalNodes=" + diagnostics.physicalNodeCount()
                + ", nativeNodes=" + countNodes(diagnostics, OperatorInvocationKind.BATCH_NATIVE)
                + ", singleNodes=" + countNodes(diagnostics, OperatorInvocationKind.SINGLE)
                + ", fusedNodes=" + diagnostics.fusedPhysicalNodeCount());
    }

    private static int countNodes(
            ExecutionDiagnostics diagnostics,
            OperatorInvocationKind kind) {
        int result = 0;
        for (NodeExecutionSnapshot node : diagnostics.nodes()) {
            if (node.operatorInvocationKind() == kind) result++;
        }
        return result;
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

    private static long[] sorted(long[] durations) {
        long[] copy = durations.clone();
        Arrays.sort(copy);
        return copy;
    }

    private static long percentile(long[] sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static String loadConfig() {
        InputStream stream = FeatureExpressionBatchComparisonDemo.class
                .getResourceAsStream(CONFIG_RESOURCE);
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

    private static final class Options {
        private static final int DEFAULT_SEQUENCE_LENGTH = 200;
        private static final int DEFAULT_GROUP_COUNT = 8;
        private static final int DEFAULT_CANDIDATES_PER_GROUP = 1000;
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
                        "Usage: FeatureExpressionBatchComparisonDemo "
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
