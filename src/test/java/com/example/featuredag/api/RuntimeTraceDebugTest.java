package com.example.featuredag.api;

import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.runtime.ConsoleRuntimeTraceObserver;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 覆盖显式开启的 DAG 描述和运行时中间值 trace；默认路径仍保持无打印。 */
public final class RuntimeTraceDebugTest {
    private static final String CONFIG = """
            {
              "feature_set_name": "runtime-trace-test",
              "version": "1",
              "features": [
                {
                  "name": "score",
                  "raw_name": "score",
                  "type": "INT",
                  "definition_type": "BASE",
                  "entity_scopes": ["USER"]
                },
                {
                  "name": "score_bucket",
                  "type": "INT",
                  "definition_type": "DERIVED",
                  "expression": "discrete(score, [10, 20])",
                  "output_policy": "OUTPUT",
                  "value_shape": "SCALAR",
                  "entity_scopes": ["USER"]
                }
              ]
            }
            """;

    @Test
    public void printsDagPlanAndRuntimeIntermediateValuesWhenExplicitlyEnabled() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream traceOutput = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        FeatureDagEngine engine = FeatureDagEngine.init(
                CONFIG,
                InitOptions.builder()
                        .environment(ExecutionEnvironment.OFFLINE)
                        .planId("runtime-trace-plan")
                        .runtimeTraceObserver(
                                new ConsoleRuntimeTraceObserver(traceOutput, 1_000))
                        .build());

        assertTrue(engine.describeLogicalDag().contains("operator=discrete"));
        assertTrue(engine.describePhysicalPlan().contains(
                "executor=GENERIC_OPERATOR/generic-operator"));
        assertTrue(engine.describePhysicalPlan().contains("operatorName=discrete"));

        GenerateResult generated = engine.generate(new OfflineGenerateRequest(
                "runtime-trace-row",
                Map.of("score", List.of(15))));

        assertEquals(List.of(1), generated.featureValues().get("score_bucket"));
        String trace = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(trace.contains("Runtime Trace runtime-trace-row [plan=runtime-trace-plan]"));
        assertTrue(trace.contains("operator=discrete"));
        assertTrue(trace.contains("input slot:"));
        assertTrue(trace.contains("= 15"));
        assertTrue(trace.contains("= 1"));
    }
}
