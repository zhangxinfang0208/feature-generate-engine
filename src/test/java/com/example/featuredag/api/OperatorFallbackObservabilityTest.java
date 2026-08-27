package com.example.featuredag.api;

import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.runtime.ExecutionDiagnostics;
import com.example.featuredag.runtime.InMemoryRuntimeObserver;
import com.example.featuredag.runtime.NodeExecutionSnapshot;
import org.junit.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 覆盖恢复计数的公共观测快照，并确保快照不携带敏感异常内容。 */
public final class OperatorFallbackObservabilityTest {

    @Test
    public void nodeDiagnosticsExposeOnlyFailureAndFallbackCounters() {
        InMemoryRuntimeObserver observer = new InMemoryRuntimeObserver(4);
        FeatureDagEngine engine = FeatureDagEngine.init(
                DerivedFeatureOperatorFallbackTest.logConfig("\"dft\": 99.0,"),
                InitOptions.builder()
                        .environment(ExecutionEnvironment.OFFLINE)
                        .planId("operator-fallback-observation")
                        .runtimeObserver(observer)
                        .build());

        GenerateResult result = engine.generate(new OfflineGenerateRequest(
                "observed-invalid-log", Map.of("score", List.of(0.0))));

        assertEquals(List.of(99.0), result.featureValues().get("score_log"));
        ExecutionDiagnostics diagnostics = observer.latest();
        NodeExecutionSnapshot operator = onlyNode(diagnostics, "generic-operator");
        NodeExecutionSnapshot output = diagnostics.nodes().stream()
                .filter(node -> "feature-output".equals(node.executorId()))
                .filter(node -> node.fallbackCount() == 1)
                .findFirst()
                .orElseThrow();
        assertEquals(1, operator.operatorFailureCount());
        assertEquals(0, operator.fallbackCount());
        assertFalse(operator.fallbackUsed());
        assertNull(operator.errorType());
        assertEquals(0, output.operatorFailureCount());
        assertEquals(1, output.fallbackCount());
        assertTrue(output.fallbackUsed());
        assertNull(output.errorType());
        assertFalse(diagnostics.toString().contains("log_base value must be greater than zero"));
        for (RecordComponent component : NodeExecutionSnapshot.class.getRecordComponents()) {
            assertFalse(Throwable.class.isAssignableFrom(component.getType()));
        }
    }

    private static NodeExecutionSnapshot onlyNode(
            ExecutionDiagnostics diagnostics,
            String executorId) {
        List<NodeExecutionSnapshot> matches = diagnostics.nodes().stream()
                .filter(node -> executorId.equals(node.executorId()))
                .toList();
        assertEquals(1, matches.size());
        return matches.get(0);
    }
}
