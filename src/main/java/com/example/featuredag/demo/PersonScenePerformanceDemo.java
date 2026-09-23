package com.example.featuredag.demo;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.GenerateResult;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OnlineGenerateRequest;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Java 8-compatible synthetic workload: one user, one scene, many items per request. */
public final class PersonScenePerformanceDemo {
    private PersonScenePerformanceDemo() {}

    static String configJson() throws Exception {
        List<Map<String, Object>> features = new ArrayList<Map<String, Object>>();
        addShared(features, "user", "USER", 100, 60);
        addShared(features, "scene", "SCENE", 60, 36);
        for (int i = 0; i < 64; i++) {
            features.add(feature("item_" + i, null, "SCALAR", "ITEM"));
            String sequence = "user_seq_" + i;
            String expression = "add(get_seq_length(slice_by_indices(" + sequence
                    + ", find_indices(" + sequence + ", item_" + i + "))), scene_scalar_"
                    + (i % 36) + ")";
            features.add(feature("cross_" + i, expression, "SCALAR", "USER", "SCENE", "ITEM"));
        }
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("feature_set_name", "person_scene_300_items");
        config.put("version", "1");
        config.put("features", features);
        return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(config);
    }

    private static void addShared(List<Map<String, Object>> features, String prefix,
            String scope, int sequences, int scalars) {
        for (int i = 0; i < sequences; i++) {
            String name = prefix + "_seq_" + i;
            features.add(feature(name, null, "SEQUENCE", scope));
            String input = "scene".equals(prefix)
                    ? "calc_delta_seq(" + name + ", scene_scalar_" + (i % scalars) + ")" : name;
            features.add(feature(prefix + "_distinct_" + i,
                    "count_distinct(" + input + ")", "SCALAR", scope));
        }
        for (int i = 0; i < scalars; i++) {
            String name = prefix + "_scalar_" + i;
            features.add(feature(name, null, "SCALAR", scope));
            features.add(feature(prefix + "_score_" + i,
                    "add(" + name + ", " + (i + 1) + ")", "SCALAR", scope));
        }
    }

    private static Map<String, Object> feature(String name, String expression,
            String shape, String... scopes) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("name", name);
        result.put("type", "INT");
        result.put("definition_type", expression == null ? "BASE" : "DERIVED");
        result.put("entity_scopes", Arrays.asList(scopes));
        result.put("value_shape", shape);
        result.put("output_policy", "OUTPUT");
        result.put(expression == null ? "raw_name" : "expression", expression == null ? name : expression);
        return result;
    }

    static FeatureDagEngine engine(String config) {
        // C3/C10：通过公共 API 构建全部输出的可达子图，由引擎推导阶段和 Batch 路由。
        Set<String> targets = new LinkedHashSet<String>();
        for (int i = 0; i < 100; i++) { targets.add("user_distinct_" + i); }
        for (int i = 0; i < 60; i++) { targets.add("user_score_" + i); targets.add("scene_distinct_" + i); }
        for (int i = 0; i < 36; i++) { targets.add("scene_score_" + i); }
        for (int i = 0; i < 64; i++) { targets.add("cross_" + i); }
        return FeatureDagEngine.init(config, InitOptions.builder()
                .environment(ExecutionEnvironment.ONLINE).targetFeatures(targets).build());
    }

    static OnlineGenerateRequest request(int length, int candidates, int cardinality, int seed) {
        if (length <= 0 || candidates <= 0 || cardinality <= 0) {
            throw new IllegalArgumentException("length, candidates and cardinality must be positive");
        }
        Map<String, List<?>> shared = new LinkedHashMap<String, List<?>>();
        sharedValues(shared, "user", 100, 60, length, seed);
        sharedValues(shared, "scene", 60, 36, length, seed + 17);
        List<Map<String, List<?>>> items = new ArrayList<Map<String, List<?>>>();
        for (int row = 0; row < candidates; row++) {
            Map<String, List<?>> item = new LinkedHashMap<String, List<?>>();
            for (int i = 0; i < 64; i++) {
                item.put("item_" + i, Collections.singletonList((row % cardinality) + i + seed));
            }
            items.add(item);
        }
        return new OnlineGenerateRequest("person-scene-" + seed, shared, items);
    }

    private static void sharedValues(Map<String, List<?>> values, String prefix,
            int sequences, int scalars, int length, int seed) {
        for (int i = 0; i < sequences; i++) {
            List<Integer> sequence = new ArrayList<Integer>(length);
            for (int j = 0; j < length; j++) {
                sequence.add((j % 512) + i + seed);
            }
            values.put(prefix + "_seq_" + i, Collections.unmodifiableList(sequence));
        }
        for (int i = 0; i < scalars; i++) {
            values.put(prefix + "_scalar_" + i, Collections.singletonList(seed + i + 1));
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 8) {
            throw new IllegalArgumentException("Expected length candidates concurrency warmups measurements poolSize cardinality outputDir");
        }
        final int length = argument(args, 0, 730, false);
        final int candidates = argument(args, 1, 300, false);
        final int concurrency = argument(args, 2, 1, false);
        final int warmups = argument(args, 3, 20, true);
        final int measurements = argument(args, 4, 100, false);
        final int poolSize = argument(args, 5, 4, false);
        final int cardinality = argument(args, 6, 300, false);
        final int total = Math.multiplyExact(concurrency, measurements);
        Path directory = Paths.get(args.length > 7 ? args[7] : "target/person-scene-performance");
        Files.createDirectories(directory);
        String config = configJson();
        Files.write(directory.resolve("features.json"), config.getBytes(StandardCharsets.UTF_8));
        long initStart = System.nanoTime();
        final FeatureDagEngine engine = engine(config);
        double initMs = (System.nanoTime() - initStart) / 1_000_000.0;
        Files.write(directory.resolve("logical-plan.txt"), engine.describeLogicalDag().getBytes(StandardCharsets.UTF_8));
        Files.write(directory.resolve("physical-plan.txt"), engine.describePhysicalPlan().getBytes(StandardCharsets.UTF_8));
        final List<OnlineGenerateRequest> pool = new ArrayList<OnlineGenerateRequest>();
        for (int i = 0; i < poolSize; i++) {
            pool.add(request(length, candidates, cardinality, i * 19));
        }
        // 数据构造和预热不进入计时；每次 generate 仍包含公共 API 解码、计算和输出编码。
        for (int i = 0; i < warmups; i++) {
            consume(engine.generate(pool.get(i % poolSize)), candidates);
        }
        System.out.println("Prepared 320 BASE + 320 DERIVED; USER/SCENE BASE=80%, sequences=50%.");
        final CountDownLatch ready = new CountDownLatch(concurrency);
        final CountDownLatch start = new CountDownLatch(1);
        final long[] durations = new long[total];
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Future<Long>> futures = new ArrayList<Future<Long>>();
        long checksum = 0;
        long elapsed;
        long[] gcBefore = gc();
        try {
            for (int worker = 0; worker < concurrency; worker++) {
                final int workerIndex = worker;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    long sum = 0;
                    for (int i = 0; i < measurements; i++) {
                        OnlineGenerateRequest request = pool.get((workerIndex + i) % poolSize);
                        long before = System.nanoTime();
                        GenerateResult result = engine.generate(request);
                        durations[workerIndex * measurements + i] = System.nanoTime() - before;
                        sum += consume(result, candidates);
                    }
                    return sum;
                }));
            }
            ready.await();
            gcBefore = gc();
            long before = System.nanoTime();
            start.countDown();
            for (Future<Long> future : futures) {
                checksum += future.get();
            }
            elapsed = System.nanoTime() - before;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
        long[] gcAfter = gc();
        Arrays.sort(durations);
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("mode", "closed-loop in-process generate; no network or external feature fetch");
        report.put("java", System.getProperty("java.version"));
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        report.put("processors", Runtime.getRuntime().availableProcessors());
        report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        report.put("jvmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        report.put("baseFeatures", 320);
        report.put("derivedFeatures", 320);
        report.put("sequenceLength", length);
        report.put("candidatesPerRequest", candidates);
        report.put("candidateParameterCardinality", cardinality);
        report.put("requestPoolSize", poolSize);
        report.put("concurrency", concurrency);
        report.put("warmupRequests", warmups);
        report.put("measuredRequests", total);
        report.put("initializationMs", initMs);
        report.put("elapsedSeconds", elapsed / 1_000_000_000.0);
        report.put("requestQps", total * 1_000_000_000.0 / elapsed);
        report.put("candidatesPerSecond", (double) total * candidates * 1_000_000_000.0 / elapsed);
        report.put("p50Ms", percentile(durations, 0.50));
        report.put("p95Ms", percentile(durations, 0.95));
        report.put("p99Ms", percentile(durations, 0.99));
        report.put("maxMs", durations[total - 1] / 1_000_000.0);
        report.put("gcCollections", gcAfter[0] - gcBefore[0]);
        report.put("gcTimeMs", gcAfter[1] - gcBefore[1]);
        report.put("heapUsedAtEndBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        report.put("checksum", checksum);
        report.put("latencySampleWarning", total < 10000 ? "Small sample; p99 is exploratory only" : "Closed-loop latency excludes external queueing");
        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("report.json").toFile(), report);
        StringBuilder samples = new StringBuilder("sorted_request_latency_ns\n");
        for (long duration : durations) {
            samples.append(duration).append('\n');
        }
        Files.write(directory.resolve("latencies.csv"), samples.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("Artifacts: " + directory.toAbsolutePath());
    }

    private static long consume(GenerateResult result, int candidates) {
        if (result.featureValues().size() != 256 || result.candidateFeatureValues().size() != candidates
                || result.candidateFeatureValues().get(0).size() != 64) {
            throw new IllegalStateException("Unexpected output shape");
        }
        return ((Number) result.candidateFeatureValues().get(candidates - 1).get("cross_0").get(0)).longValue();
    }

    private static long[] gc() {
        long count = 0;
        long time = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0, bean.getCollectionCount());
            time += Math.max(0, bean.getCollectionTime());
        }
        return new long[] {count, time};
    }

    private static double percentile(long[] sorted, double quantile) {
        return sorted[(int) Math.ceil(sorted.length * quantile) - 1] / 1_000_000.0;
    }

    private static int argument(String[] args, int index, int fallback, boolean allowZero) {
        int value = args.length > index ? Integer.parseInt(args[index]) : fallback;
        if (value < (allowZero ? 0 : 1)) {
            throw new IllegalArgumentException("Invalid argument at index " + index);
        }
        return value;
    }
}
