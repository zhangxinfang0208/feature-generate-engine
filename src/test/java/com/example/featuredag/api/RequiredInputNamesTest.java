package com.example.featuredag.api;

import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.runtime.InMemoryRuntimeObserver;
import com.example.featuredag.runtime.ObservabilityOptions;
import com.example.featuredag.runtime.ObservationDetailLevel;
import com.example.featuredag.runtime.RuntimeObservabilityController;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public final class RequiredInputNamesTest {
    private static final String CONFIG = """
            {"feature_set_name":"input-contract","version":"1","features":[
              {"name":"user_score","raw_name":"legacy_score","type":"INT",
               "definition_type":"BASE","entity_scopes":["USER"],"dft":2},
              {"name":"item_score","type":"INT","definition_type":"BASE",
               "entity_scopes":["ITEM"]},
              {"name":"unused","type":"INT","definition_type":"BASE",
               "entity_scopes":["USER"]},
              {"name":"intermediate","type":"INT","definition_type":"DERIVED",
               "expression":"add(user_score, 1)","output_policy":"INTERNAL_ONLY"},
              {"name":"score","type":"INT","definition_type":"DERIVED",
               "expression":"add(intermediate, item_score)","output_policy":"OUTPUT"},
              {"name":"constant","type":"INT","definition_type":"DERIVED",
               "expression":"add(1, 2)","output_policy":"OUTPUT"}
            ]}
            """;

    @Test
    public void reportsReachableRequestKeysAndFiltersBeforeConversion() {
        FeatureDagEngine engine = engine(ExecutionEnvironment.ONLINE, "score");
        assertEquals(Set.of("user_score", "item_score"), engine.requiredInputNames());
        assertEquals(Set.of("user_score"), engine.requiredSharedInputNames());
        assertEquals(Set.of("item_score"), engine.requiredCandidateInputNames());
        Map<String, List<?>> shared = select(
                Map.of("user_score", 4, "unused", new Object()), engine.requiredSharedInputNames());
        Map<String, List<?>> item = select(
                Map.of("item_score", 5, "unused", new Object()), engine.requiredCandidateInputNames());
        assertEquals(List.of(10L), engine.generate(new OnlineGenerateRequest(
                "filtered", shared, List.of(item))).candidateFeatureValues().get(0).get("score"));
        // 带默认值的源仍在依赖集合中；缺失时保留运行时默认值语义。
        assertEquals(List.of(8L), engine.generate(new OnlineGenerateRequest(
                "default", Map.of(), List.of(item))).candidateFeatureValues().get(0).get("score"));
        assertSame(engine.requiredInputNames(), engine.requiredInputNames());
        assertThrows(UnsupportedOperationException.class, () -> engine.requiredInputNames().clear());
        assertThrows(UnsupportedOperationException.class, () -> engine.requiredSharedInputNames().clear());
        assertThrows(UnsupportedOperationException.class, () -> engine.requiredCandidateInputNames().clear());
    }

    @Test
    public void supportsOfflineAndConstantGraphs() {
        FeatureDagEngine offline = engine(ExecutionEnvironment.OFFLINE, "score");
        assertEquals(Set.of("user_score", "item_score"), offline.requiredInputNames());
        assertEquals(List.of(10L), offline.generate(new OfflineGenerateRequest("row",
                select(Map.of("user_score", 4, "item_score", 5), offline.requiredInputNames())))
                .featureValues().get("score"));
        FeatureDagEngine constant = engine(ExecutionEnvironment.ONLINE, "constant");
        assertTrue(constant.requiredInputNames().isEmpty());
        assertTrue(constant.requiredSharedInputNames().isEmpty());
        assertTrue(constant.requiredCandidateInputNames().isEmpty());
    }

    @Test
    public void diagnosticsCanBeEnabledWithoutRebuildingEngine() {
        InMemoryRuntimeObserver observer = new InMemoryRuntimeObserver(10);
        RuntimeObservabilityController controller = new RuntimeObservabilityController(
                ObservabilityOptions.builder().enabled(false).sampleRate(1.0)
                        .detailLevel(ObservationDetailLevel.NODE).build());
        FeatureDagEngine engine = FeatureDagEngine.init(CONFIG, InitOptions.builder()
                .environment(ExecutionEnvironment.OFFLINE).targetFeatures(Set.of("constant"))
                .runtimeObserver(observer).observabilityController(controller).build());
        engine.generate(new OfflineGenerateRequest("disabled", Map.of()));
        assertEquals(0, observer.receivedCount());
        controller.setEnabled(true);
        engine.generate(new OfflineGenerateRequest("enabled", Map.of()));
        assertEquals(1, observer.receivedCount());
        assertEquals("enabled", observer.latest().executionId());
        assertFalse(observer.latest().nodes().isEmpty());
        controller.setEnabled(false);
        engine.generate(new OfflineGenerateRequest("disabled-again", Map.of()));
        assertEquals(1, observer.receivedCount());
    }

    private static FeatureDagEngine engine(ExecutionEnvironment environment, String target) {
        return FeatureDagEngine.init(CONFIG, InitOptions.builder()
                .environment(environment).targetFeatures(Set.of(target)).build());
    }

    private static Map<String, List<?>> select(Map<String, Object> values, Set<String> names) {
        Map<String, List<?>> result = new LinkedHashMap<>();
        for (String name : names) {
            if (values.containsKey(name)) {
                // 不消费的值故意不可转为 Integer，确保过滤发生在转换之前。
                result.put(name, List.of((Integer) values.get(name)));
            }
        }
        return result;
    }
}
