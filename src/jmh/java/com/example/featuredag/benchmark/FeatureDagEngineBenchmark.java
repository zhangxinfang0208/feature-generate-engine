package com.example.featuredag.benchmark;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.GenerateResult;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OnlineBatchGenerateRequest;
import com.example.featuredag.api.OnlineBatchGenerateResult;
import com.example.featuredag.api.OnlineGenerateRequest;
import com.example.featuredag.api.OnlineRequestGroup;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.runtime.InMemoryRuntimeObserver;
import com.example.featuredag.runtime.ObservabilityOptions;
import com.example.featuredag.runtime.ObservationDetailLevel;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(2)
public class FeatureDagEngineBenchmark {
    private static final Set<String> TARGETS = Set.of(
            "user_tag_count", "score", "matching_tag_count");

    @Benchmark
    public GenerateResult onlineSingle(SingleRequestState state) {
        return state.engine.generate(state.request);
    }

    @Benchmark
    public OnlineBatchGenerateResult onlineGroupedBatch(GroupedBatchState state) {
        return state.engine.generateBatch(state.request);
    }

    @State(Scope.Benchmark)
    public static class SingleRequestState {
        @Param({"1", "10", "100", "1000"})
        public int candidateCount;

        @Param({"1", "10"})
        public int distinctKeyCount;

        @Param({"OFF", "BASIC", "CACHE", "NODE"})
        public String observabilityMode;

        private FeatureDagEngine engine;
        private OnlineGenerateRequest request;

        @Setup(Level.Trial)
        public void setup() {
            engine = onlineEngine("jmh-online-single", observabilityMode);
            request = new OnlineGenerateRequest(
                    "jmh-single-request",
                    sharedValues(),
                    candidates(candidateCount, distinctKeyCount));
        }
    }

    @State(Scope.Benchmark)
    public static class GroupedBatchState {
        @Param({"1", "8", "32"})
        public int groupCount;

        @Param({"1", "10", "100"})
        public int candidatesPerGroup;

        @Param({"OFF", "BASIC", "CACHE", "NODE"})
        public String observabilityMode;

        private FeatureDagEngine engine;
        private OnlineBatchGenerateRequest request;

        @Setup(Level.Trial)
        public void setup() {
            engine = onlineEngine("jmh-online-grouped", observabilityMode);
            List<OnlineRequestGroup> groups = new ArrayList<>(groupCount);
            for (int groupIndex = 0; groupIndex < groupCount; groupIndex++) {
                groups.add(new OnlineRequestGroup(
                        "jmh-group-" + groupIndex,
                        sharedValues(),
                        candidates(candidatesPerGroup, 10)));
            }
            request = new OnlineBatchGenerateRequest("jmh-grouped-request", groups);
        }
    }

    private static FeatureDagEngine onlineEngine(
            String planId,
            String observabilityMode) {
        InitOptions.Builder options = InitOptions.builder()
                .environment(ExecutionEnvironment.ONLINE)
                .planId(planId)
                .targetFeatures(TARGETS);
        if (!observabilityMode.equals("OFF")) {
            ObservationDetailLevel detailLevel = ObservationDetailLevel.valueOf(observabilityMode);
            options.observabilityOptions(ObservabilityOptions.builder()
                            .sampleRate(1.0)
                            .detailLevel(detailLevel)
                            .build())
                    .runtimeObserver(new InMemoryRuntimeObserver(1));
        }
        return FeatureDagEngine.init(
                loadDemoConfig(),
                options.build());
    }

    private static Map<String, List<?>> sharedValues() {
        List<String> userTags = new ArrayList<>(100);
        for (int index = 0; index < 100; index++) {
            userTags.add("tag-" + (index % 10));
        }
        return Map.of(
                "user_multiplier", List.of(2.0),
                "user_tags", List.copyOf(userTags));
    }

    private static List<Map<String, List<?>>> candidates(
            int candidateCount,
            int distinctKeyCount) {
        List<Map<String, List<?>>> result = new ArrayList<>(candidateCount);
        for (int index = 0; index < candidateCount; index++) {
            result.add(Map.of(
                    "item_value", List.of((double) index + 1.0),
                    "item_tag", List.of("tag-" + (index % distinctKeyCount))));
        }
        return List.copyOf(result);
    }

    private static String loadDemoConfig() {
        try (InputStream input = FeatureDagEngineBenchmark.class
                .getResourceAsStream("/demo/config.json")) {
            if (input == null) throw new IllegalStateException("Missing /demo/config.json");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load benchmark config", error);
        }
    }
}
