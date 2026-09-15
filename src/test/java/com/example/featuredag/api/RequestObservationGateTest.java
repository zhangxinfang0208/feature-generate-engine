package com.example.featuredag.api;

import com.example.featuredag.config.FeatureConfig;
import com.example.featuredag.config.FeatureSetConfig;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.runtime.RuntimeObserver;
import com.example.featuredag.runtime.RuntimeTraceObserver;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 请求开关测试不依赖 JSON 解析；直接构造真实 DTO，执行真实构图/运行时。 */
public class RequestObservationGateTest {
    @Test
    public void defaultGatePreservesExistingObservers() throws Exception {
        AtomicInteger diagnostics = new AtomicInteger();
        AtomicInteger traces = new AtomicInteger();
        FeatureDagEngine engine = engine(options(diagnostics, traces).build());
        engine.generate(request("default", 7L));
        equal(1, diagnostics.get());
        equal(1, traces.get());
    }

    @Test
    public void gateIsEvaluatedOnceOnCallerThreadAndSharedByBothObservers() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger diagnostics = new AtomicInteger();
        AtomicInteger traces = new AtomicInteger();
        Thread caller = Thread.currentThread();
        FeatureDagEngine engine = engine(options(diagnostics, traces)
                .requestObservationEnabled(() -> {
                    equal(caller, Thread.currentThread());
                    return calls.incrementAndGet() == 1;
                }).build());
        equal(List.of(7L), engine.generate(request("probe", 7L)).featureValues().get("out"));
        equal(1, calls.get());
        equal(1, diagnostics.get());
        equal(1, traces.get());
    }

    @Test
    public void disabledRequestSkipsBothObserversAndObservationObject() throws Exception {
        AtomicInteger diagnostics = new AtomicInteger();
        AtomicInteger traces = new AtomicInteger();
        FeatureDagEngine engine = engine(options(diagnostics, traces)
                .requestObservationEnabled(() -> false).build());
        equal(List.of(7L), engine.generate(request("normal", 7L)).featureValues().get("out"));
        equal(0, diagnostics.get());
        equal(0, traces.get());
        Method start = FeatureDagEngine.class.getDeclaredMethod("startObservation",
                String.class, int.class, int.class, int.class, boolean.class);
        start.setAccessible(true);
        equal(null, start.invoke(engine, "normal", 1, 1, 0, false));
    }

    @Test
    public void brokenSupplierDisablesObservationWithoutFailingCalculation() throws Exception {
        AtomicInteger diagnostics = new AtomicInteger();
        AtomicInteger traces = new AtomicInteger();
        FeatureDagEngine engine = engine(options(diagnostics, traces)
                .requestObservationEnabled(() -> { throw new IllegalStateException("context unavailable"); }).build());
        equal(List.of(8L), engine.generate(request("normal", 8L)).featureValues().get("out"));
        equal(0, diagnostics.get());
        equal(0, traces.get());
    }

    @Test
    public void noopAndTraceOnlyRemainIndependent() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        FeatureDagEngine noop = engine(InitOptions.builder().environment(ExecutionEnvironment.ONLINE)
                .requestObservationEnabled(() -> { calls.incrementAndGet(); return true; }).build());
        noop.generate(request("noop", 1L));
        equal(0, calls.get());
        AtomicInteger traces = new AtomicInteger();
        FeatureDagEngine traceOnly = engine(InitOptions.builder().environment(ExecutionEnvironment.ONLINE)
                .runtimeObserver(RuntimeObserver.noop())
                .runtimeTraceObserver((id, plan, result) -> traces.incrementAndGet())
                .requestObservationEnabled(() -> { calls.incrementAndGet(); return true; }).build());
        traceOnly.generate(request("trace", 1L));
        equal(1, calls.get());
        equal(1, traces.get());
        AtomicInteger diagnostics = new AtomicInteger();
        FeatureDagEngine diagnosticsOnly = engine(InitOptions.builder().environment(ExecutionEnvironment.ONLINE)
                .runtimeObserver(d -> diagnostics.incrementAndGet()).runtimeTraceObserver(RuntimeTraceObserver.noop())
                .requestObservationEnabled(() -> true).build());
        diagnosticsOnly.generate(request("diagnostics", 1L));
        equal(1, diagnostics.get());
    }

    @Test
    public void offlineAndBothBatchEntriesUseOneGateDecision() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger diagnostics = new AtomicInteger();
        AtomicInteger traces = new AtomicInteger();
        FeatureDagEngine offline = engine(options(diagnostics, traces)
                .environment(ExecutionEnvironment.OFFLINE)
                .requestObservationEnabled(() -> { calls.incrementAndGet(); return true; }).build());
        offline.generate(new OfflineGenerateRequest("single", Map.of("raw", List.of(1L))));
        offline.generateBatch(new OfflineBatchGenerateRequest("batch", List.of(Map.of("raw", List.of(2L)))));
        FeatureDagEngine online = engine(options(diagnostics, traces)
                .requestObservationEnabled(() -> { calls.incrementAndGet(); return true; }).build());
        online.generateBatch(new OnlineBatchGenerateRequest("groups", List.of(
                new OnlineRequestGroup("a", Map.of("raw", List.of(1L)), List.of(Map.of())),
                new OnlineRequestGroup("b", Map.of("raw", List.of(2L)), List.of(Map.of())))));
        equal(3, calls.get());
        equal(3, diagnostics.get());
        equal(3, traces.get());
    }

    @Test
    public void failedNormalRequestsNeverPublishEvenWithFailureCaptureEnabled() throws Exception {
        ThreadLocal<Boolean> probe = new ThreadLocal<>();
        AtomicInteger diagnostics = new AtomicInteger();
        AtomicInteger traces = new AtomicInteger();
        FeatureDagEngine engine = engine(options(diagnostics, traces)
                .requestObservationEnabled(() -> Boolean.TRUE.equals(probe.get())).build());
        try {
            probe.set(false);
            expectFailure(engine, "normal");
            equal(0, diagnostics.get());
            equal(0, traces.get());
            probe.set(true);
            expectFailure(engine, "probe");
            equal(1, diagnostics.get());
            equal(1, traces.get());
        } finally {
            probe.remove();
        }
    }

    @Test
    public void mixedConcurrentRequestsRemainIsolatedOnReusedWorkers() throws Exception {
        ThreadLocal<Boolean> probe = new ThreadLocal<>();
        AtomicInteger calls = new AtomicInteger();
        Set<String> diagnostics = ConcurrentHashMap.newKeySet();
        Set<String> traces = ConcurrentHashMap.newKeySet();
        FeatureDagEngine engine = engine(InitOptions.builder().environment(ExecutionEnvironment.ONLINE)
                .requestObservationEnabled(() -> { calls.incrementAndGet(); return Boolean.TRUE.equals(probe.get()); })
                .runtimeObserver(d -> diagnostics.add(d.executionId()))
                .runtimeTraceObserver((id, plan, result) -> traces.add(id)).build());
        Set<String> expected = ConcurrentHashMap.newKeySet();
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 800; i++) {
                int value = i;
                if (i % 2 == 0) expected.add("r-" + i);
                futures.add(pool.submit(() -> {
                    try {
                        probe.set(value % 2 == 0);
                        equal(List.of((long) value), engine.generate(request("r-" + value, value)).featureValues().get("out"));
                    } finally {
                        probe.remove();
                    }
                }));
            }
            for (var future : futures) future.get();
        }
        equal(800, calls.get());
        equal(expected, diagnostics);
        equal(expected, traces);
    }

    private static InitOptions.Builder options(AtomicInteger diagnostics, AtomicInteger traces) {
        return InitOptions.builder().environment(ExecutionEnvironment.ONLINE)
                .runtimeObserver(d -> diagnostics.incrementAndGet())
                .runtimeTraceObserver((id, plan, result) -> traces.incrementAndGet());
    }

    private static OnlineGenerateRequest request(String id, long value) {
        return new OnlineGenerateRequest(id, Map.of("raw", List.of(value)), List.of(Map.of()));
    }

    private static void expectFailure(FeatureDagEngine engine, String id) {
        try {
            engine.generate(new OnlineGenerateRequest(id, Map.of(), List.of(Map.of())));
        } catch (FeatureGenerationException expected) {
            return;
        }
        throw new AssertionError("expected missing source failure");
    }

    private static FeatureDagEngine engine(InitOptions options) throws Exception {
        FeatureConfig raw = new FeatureConfig();
        field(raw, "name", "raw"); field(raw, "type", "BIGINT"); field(raw, "definitionType", "BASE");
        field(raw, "valueShape", "SCALAR"); field(raw, "entityScopes", List.of("USER"));
        FeatureConfig out = new FeatureConfig();
        field(out, "name", "out"); field(out, "type", "BIGINT"); field(out, "definitionType", "DERIVED");
        field(out, "expression", "raw"); field(out, "outputPolicy", "OUTPUT");
        FeatureSetConfig config = new FeatureSetConfig();
        field(config, "featureSetName", "request-observation"); field(config, "version", "1");
        field(config, "features", List.of(raw, out));
        Method initialize = FeatureDagEngine.class.getDeclaredMethod("initialize", FeatureSetConfig.class, InitOptions.class);
        initialize.setAccessible(true);
        return (FeatureDagEngine) initialize.invoke(null, config, options);
    }

    private static void field(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("expected=" + expected + ", actual=" + actual);
        }
    }
}
