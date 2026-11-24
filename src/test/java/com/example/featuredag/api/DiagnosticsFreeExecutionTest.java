package com.example.featuredag.api;

import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.runtime.ExecutionDiagnostics;
import com.example.featuredag.runtime.ExecutionStatus;
import com.example.featuredag.runtime.ObservabilityOptions;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 观测出口关闭时引擎不做逐节点计时与状态快照，但输出、失败归因必须与开启观测时完全一致；
 * 开启观测时每个请求仍得到覆盖全部物理节点的诊断。
 */
public class DiagnosticsFreeExecutionTest {
    private static final String CONFIG = """
            {"feature_set_name":"diagnostics-free","version":"1","features":[
              {"name":"x","raw_name":"x","definition_type":"BASE","type":"INT","value_shape":"SEQUENCE","entity_scopes":["USER"]},
              {"name":"t","raw_name":"t","definition_type":"BASE","type":"INT","value_shape":"SCALAR","entity_scopes":["ITEM"]},
              {"name":"score","raw_name":"score","definition_type":"BASE","type":"DOUBLE","value_shape":"SCALAR","entity_scopes":["USER"]},
              {"name":"picked","definition_type":"DERIVED","type":"INT","value_shape":"SEQUENCE","expression":"slice_by_indices(x, find_indices(x, t))","output_policy":"OUTPUT"},
              {"name":"count","definition_type":"DERIVED","type":"INT","value_shape":"SCALAR","expression":"count_distinct(x)","output_policy":"OUTPUT"},
              {"name":"score_log","definition_type":"DERIVED","type":"DOUBLE","value_shape":"SCALAR","expression":"log_base(score, 2, 1000)","output_policy":"OUTPUT"}
            ]}
            """;

    @Test
    public void outputsAreIdenticalWithAndWithoutDiagnosticsCollection() {
        List<ExecutionDiagnostics> collected = new CopyOnWriteArrayList<>();
        FeatureDagEngine observed = FeatureDagEngine.init(CONFIG, InitOptions.builder()
                .environment(ExecutionEnvironment.ONLINE).planId("diagnostics-free")
                .runtimeObserver(collected::add)
                .observabilityOptions(ObservabilityOptions.builder().enabled(true).build())
                .build());
        FeatureDagEngine plain = FeatureDagEngine.init(CONFIG, InitOptions.builder()
                .environment(ExecutionEnvironment.ONLINE).planId("diagnostics-free").build());

        OnlineGenerateRequest single = new OnlineGenerateRequest(
                "single", Map.of("x", List.of(1, 2, 1), "score", List.of(8.0)),
                List.of(Map.of("t", List.of(1)), Map.of("t", List.of(2))));
        OnlineBatchGenerateRequest batch = new OnlineBatchGenerateRequest("batch", List.of(
                new OnlineRequestGroup("g1", Map.of("x", List.of(1, 2, 1), "score", List.of(8.0)),
                        List.of(Map.of("t", List.of(1)))),
                new OnlineRequestGroup("g2", Map.of("x", List.of(3, 3), "score", List.of(4.0)),
                        List.of(Map.of("t", List.of(3)), Map.of("t", List.of(9))))));
        OnlineColumnarGenerateRequest columnar = new OnlineColumnarGenerateRequest(
                "columnar", Map.of("x", List.of(1, 2, 1), "score", List.of(8.0)),
                List.of("c1", "c2"), Map.of("t", List.of(1, 2)));

        GenerateResult observedSingle = observed.generate(single);
        GenerateResult plainSingle = plain.generate(single);
        assertEquals(observedSingle.featureValues(), plainSingle.featureValues());
        assertEquals(observedSingle.candidateFeatureValues(), plainSingle.candidateFeatureValues());
        assertEquals(List.of(List.of(1, 1), List.of(2)), plainSingle.candidateFeatureValues().stream()
                .map(candidate -> candidate.get("picked")).toList());

        OnlineBatchGenerateResult observedBatch = observed.generateBatch(batch);
        OnlineBatchGenerateResult plainBatch = plain.generateBatch(batch);
        assertEquals(observedBatch.groupResults().stream().map(GenerateResult::featureValues).toList(),
                plainBatch.groupResults().stream().map(GenerateResult::featureValues).toList());
        assertEquals(observedBatch.groupResults().stream().map(GenerateResult::candidateFeatureValues).toList(),
                plainBatch.groupResults().stream().map(GenerateResult::candidateFeatureValues).toList());
        assertEquals(List.of(List.of(1, 1)), plainBatch.groupResults().get(0).candidateFeatureValues().stream()
                .map(candidate -> candidate.get("picked")).toList());
        assertEquals(List.of(List.of(3, 3), List.of()), plainBatch.groupResults().get(1).candidateFeatureValues().stream()
                .map(candidate -> candidate.get("picked")).toList());

        assertEquals(observed.generateColumnar(columnar).candidateFeatureValues(),
                plain.generateColumnar(columnar).candidateFeatureValues());

        // 观测开启时每个请求都产出覆盖全部物理节点的诊断。
        assertEquals(3, collected.size());
        for (ExecutionDiagnostics diagnostics : collected) {
            assertEquals(ExecutionStatus.SUCCESS, diagnostics.status());
            assertEquals(diagnostics.physicalNodeCount(), diagnostics.nodes().size());
            assertTrue(diagnostics.nodes().stream().allMatch(node -> node.status() == ExecutionStatus.SUCCESS));
        }
        // 诊断里的节点数与引擎公开的物理计划规模一致。
        assertEquals(plain.describePhysicalPlan().lines()
                        .filter(line -> line.startsWith("- physical:")).count(),
                collected.get(0).physicalNodeCount());
    }

    @Test
    public void failuresAreAttributedToTheAffectedFeaturesWithoutDiagnostics() {
        List<ExecutionDiagnostics> collected = new ArrayList<>();
        FeatureDagEngine plain = FeatureDagEngine.init(CONFIG, InitOptions.builder()
                .environment(ExecutionEnvironment.ONLINE).planId("diagnostics-failure").build());
        FeatureDagEngine observed = FeatureDagEngine.init(CONFIG, InitOptions.builder()
                .environment(ExecutionEnvironment.ONLINE).planId("diagnostics-failure")
                .runtimeObserver(collected::add)
                .observabilityOptions(ObservabilityOptions.builder()
                        .enabled(true).sampleRate(0.0).captureFailuresAlways(true).build())
                .build());
        // log_base 的底数为 0：算子失败且 score_log 没有默认值，请求整体失败。
        OnlineGenerateRequest invalid = new OnlineGenerateRequest(
                "invalid", Map.of("x", List.of(1, 2, 1), "score", List.of(0.0)), List.of(Map.of("t", List.of(1))));

        FeatureGenerationException plainFailure = assertThrows(FeatureGenerationException.class,
                () -> plain.generate(invalid));
        FeatureGenerationException observedFailure = assertThrows(FeatureGenerationException.class,
                () -> observed.generate(invalid));

        assertEquals(List.of("score_log"), plainFailure.featureNames());
        assertEquals(observedFailure.featureNames(), plainFailure.featureNames());
        assertEquals(observedFailure.getMessage(), plainFailure.getMessage());
        assertEquals(observedFailure.getCause().getClass(), plainFailure.getCause().getClass());
        assertEquals(1, collected.size());
        assertEquals(ExecutionStatus.FAILED, collected.get(0).status());
        assertTrue(collected.get(0).nodes().stream().anyMatch(node -> node.status() == ExecutionStatus.FAILED));
    }
}
