package com.example.featuredag.runtime;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.FeatureDefinition;
import com.example.featuredag.definition.FeatureRole;
import com.example.featuredag.definition.OutputPolicy;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.operator.OperatorDefinition;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.physical.ExecutorType;
import com.example.featuredag.physical.PhysicalNode;
import com.example.featuredag.physical.PhysicalPlan;
import com.example.featuredag.physical.PhysicalPlanner;
import com.example.featuredag.physical.rewrite.PhysicalRewriteRegistry;
import com.example.featuredag.planning.LogicalDagOptimizer;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class DerivedFeatureOperatorFallbackRuntimeTest {
    @Test
    public void nestedFailureUsesWholeFeatureDefaultAndSkipsDownstream() {
        OperatorRegistry registry = OperatorRegistry.standard();
        RuntimeRun run = executeSingle(
                registry,
                List.of(
                        FeatureDefinition.raw("score", DataType.DOUBLE, EntityScope.USER, null),
                        derived("result", DataType.INT, "add(to_int(score), 10)", -1,
                                OutputPolicy.OUTPUT)),
                Map.of("score", 2.5e9),
                Set.of("result"));

        assertEquals(Integer.valueOf(-1), run.result().feature("result").raw());
        RuntimeNodeState outputState = featureOutputState(run, "result");
        assertTrue(outputState.fallbackUsed());
        assertEquals(1, outputState.fallbackCount());
        assertEquals(1, operatorState(run, "to_int").operatorFailureCount());
        assertEquals(0, operatorState(run, "add").operatorFailureCount());
    }

    @Test
    public void intermediateDefaultBecomesNormalDownstreamInput() {
        OperatorRegistry registry = OperatorRegistry.standard();
        RuntimeRun run = executeSingle(
                registry,
                List.of(
                        FeatureDefinition.raw("score", DataType.DOUBLE, EntityScope.USER, null),
                        derived("safe_score", DataType.INT, "to_int(score)", 0,
                                OutputPolicy.INTERNAL_ONLY),
                        derived("result", DataType.BIGINT, "add(safe_score, 10)", -1L,
                                OutputPolicy.OUTPUT)),
                Map.of("score", 2.5e9),
                Set.of("result"));

        assertEquals(Long.valueOf(10L), run.result().feature("result").raw());
        assertEquals(1, featureOutputState(run, "safe_score").fallbackCount());
        assertEquals(0, featureOutputState(run, "result").fallbackCount());
    }

    @Test
    public void sharedFailureUsesEachFeatureDefault() {
        OperatorRegistry registry = OperatorRegistry.standard();
        RuntimeRun run = executeSingle(
                registry,
                List.of(
                        FeatureDefinition.raw("score", DataType.DOUBLE, EntityScope.USER, null),
                        derived("left", DataType.INT, "to_int(score)", -1,
                                OutputPolicy.OUTPUT),
                        derived("right", DataType.INT, "to_int(score)", 999,
                                OutputPolicy.OUTPUT)),
                Map.of("score", 2.5e9),
                Set.of("left", "right"));

        assertEquals(Integer.valueOf(-1), run.result().feature("left").raw());
        assertEquals(Integer.valueOf(999), run.result().feature("right").raw());
        assertEquals(1, operatorState(run, "to_int").operatorFailureCount());
    }

    @Test
    public void failureWithoutDefaultNamesFeatureAndPreservesCause() {
        OperatorRegistry registry = OperatorRegistry.standard();
        List<FeatureDefinition> definitions = List.of(
                FeatureDefinition.raw("score", DataType.DOUBLE, EntityScope.USER, null),
                derived("result", DataType.INT, "to_int(score)", null, OutputPolicy.OUTPUT));

        FeatureEvaluationException failure = assertThrows(
                FeatureEvaluationException.class,
                () -> executeSingle(
                        registry,
                        definitions,
                        Map.of("score", 2.5e9),
                        Set.of("result")));

        assertEquals("result", failure.featureName());
        assertEquals("scalar value", failure.location());
        assertTrue(failure.getCause() instanceof IllegalArgumentException);
    }

    @Test
    public void jvmErrorIsNeverConvertedToDefault() {
        OperatorRegistry registry = OperatorRegistry.standard().register(new FatalOperator());
        List<FeatureDefinition> definitions = List.of(
                FeatureDefinition.raw("score", DataType.DOUBLE, EntityScope.USER, null),
                derived("result", DataType.INT, "fatal(score)", -1, OutputPolicy.OUTPUT));

        AssertionError failure = assertThrows(
                AssertionError.class,
                () -> executeSingle(
                        registry,
                        definitions,
                        Map.of("score", 1.0),
                        Set.of("result")));

        assertEquals("fatal", failure.getMessage());
    }

    private static FeatureDefinition derived(
            String name,
            DataType dataType,
            String expression,
            Object defaultValue,
            OutputPolicy outputPolicy) {
        return FeatureDefinition.builder()
                .name(name)
                .role(FeatureRole.DERIVED)
                .dataType(dataType)
                .expressionContent(expression)
                .defaultValue(defaultValue)
                .outputPolicy(outputPolicy)
                .build();
    }

    private static RuntimeRun executeSingle(
            OperatorRegistry registry,
            List<FeatureDefinition> definitions,
            Map<String, Object> inputs,
            Set<String> targets) {
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry)
                .build(definitions, targets);
        PhysicalPlan plan = new PhysicalPlanner(registry, new PhysicalRewriteRegistry()).plan(
                new LogicalDagOptimizer(registry).analyze(dag),
                ExecutionEnvironment.OFFLINE,
                "single-fallback-plan");
        ExecutionResult result = new DagRuntime(registry).execute(
                plan,
                ExecutionContext.offlineRow("single-fallback-run", inputs));
        return new RuntimeRun(plan, result);
    }

    private static RuntimeNodeState featureOutputState(RuntimeRun run, String featureName) {
        PhysicalNode node = run.plan().nodes().stream()
                .filter(candidate -> candidate.executorType() == ExecutorType.FEATURE_OUTPUT)
                .filter(candidate -> featureName.equals(
                        candidate.executorConfig().get("featureName")))
                .findFirst()
                .orElseThrow();
        return run.result().nodeStates().get(node.physicalNodeId());
    }

    private static RuntimeNodeState operatorState(RuntimeRun run, String operatorName) {
        PhysicalNode node = run.plan().nodes().stream()
                .filter(candidate -> candidate.executorType() == ExecutorType.GENERIC_OPERATOR)
                .filter(candidate -> operatorName.equals(
                        candidate.executorConfig().get("operatorName")))
                .findFirst()
                .orElseThrow();
        return run.result().nodeStates().get(node.physicalNodeId());
    }

    private record RuntimeRun(PhysicalPlan plan, ExecutionResult result) {
    }

    private static final class FatalOperator implements OperatorDefinition {
        @Override public String name() { return "fatal"; }
        @Override public int minArguments() { return 1; }
        @Override public int maxArguments() { return 1; }
        @Override public boolean deterministic() { return true; }
        @Override public boolean supportsSequenceView() { return false; }

        @Override
        public OperatorInference infer(List<OperatorInputMetadata> inputs) {
            return new OperatorInference(DataType.INT, inputs.get(0).entityScopes(), ValueShape.SCALAR);
        }

        @Override
        public Object evaluate(List<Object> arguments) {
            throw new AssertionError("fatal");
        }
    }
}
