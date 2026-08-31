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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public final class OperatorBatchFailureFallbackRuntimeTest {
    @Test
    public void offlineBatchReplacesOnlyOverflowRow() {
        OperatorRegistry registry = OperatorRegistry.standard();
        RuntimeRun run = execute(
                registry,
                scalarDefinitions(EntityScope.USER, "add(to_int(score), 10)", -1),
                ExecutionEnvironment.OFFLINE,
                ExecutionContext.offlineBatch(
                        "offline-batch",
                        List.of(
                                Map.of("score", 12.8),
                                Map.of("score", 2.5e9),
                                Map.of("score", 3.6))),
                Set.of("result"));

        OfflineBatchValue values = (OfflineBatchValue) run.result().feature("result");
        assertEquals(Arrays.<Object>asList(22L, -1, 13L), values.values());
        assertEquals(1, featureOutputState(run, "result").fallbackCount());
        assertEquals(1, operatorState(run, "to_int").operatorFailureCount());
        assertEquals(0, operatorState(run, "add").operatorFailureCount());
    }

    @Test
    public void onlineCandidatesReplaceOnlyInvalidCandidate() {
        OperatorRegistry registry = OperatorRegistry.standard();
        RuntimeRun run = execute(
                registry,
                scalarDefinitions(EntityScope.ITEM, "add(to_int(score), 10)", -1),
                ExecutionEnvironment.ONLINE,
                ExecutionContext.onlineRequest(
                        "online-request",
                        Map.of(),
                        List.of(
                                Map.of("score", 12.8),
                                Map.of("score", 2.5e9),
                                Map.of("score", 3.6))),
                Set.of("result"));

        CandidateVectorValue values = (CandidateVectorValue) run.result().feature("result");
        assertEquals(Arrays.<Object>asList(22L, -1, 13L), values.values());
        assertEquals(1, featureOutputState(run, "result").fallbackCount());
    }

    @Test
    public void onlineGroupedBatchSkipsFailedCandidateInDownstreamKernel() {
        CountingPlusTenOperator counting = new CountingPlusTenOperator();
        OperatorRegistry registry = OperatorRegistry.standard().register(counting);
        RuntimeRun run = execute(
                registry,
                scalarDefinitions(EntityScope.ITEM, "count_plus_ten(to_int(score))", -1),
                ExecutionEnvironment.ONLINE,
                ExecutionContext.onlineBatch(
                        "online-batch",
                        List.of("group-0", "group-1"),
                        List.of(Map.of(), Map.of()),
                        List.of(
                                List.of(
                                        Map.of("score", 12.8),
                                        Map.of("score", 2.5e9)),
                                List.of(Map.of("score", 3.6)))),
                Set.of("result"));

        CandidateBatchValue values = (CandidateBatchValue) run.result().feature("result");
        assertEquals(Arrays.asList(22, -1, 13), values.values());
        assertEquals(2, counting.invocationCount());
        assertEquals(1, featureOutputState(run, "result").fallbackCount());
    }

    @Test
    public void nativeDeltaDefaultsOnlyNonFiniteBaseRow() {
        OperatorRegistry registry = OperatorRegistry.standard();
        List<FeatureDefinition> definitions = List.of(
                FeatureDefinition.builder()
                        .name("numbers")
                        .role(FeatureRole.RAW)
                        .dataType(DataType.DOUBLE)
                        .addEntityScope(EntityScope.USER)
                        .sourceBinding("numbers")
                        .declaredValueShape(ValueShape.SEQUENCE)
                        .build(),
                FeatureDefinition.raw("base", DataType.DOUBLE, EntityScope.USER, null),
                FeatureDefinition.builder()
                        .name("result")
                        .role(FeatureRole.DERIVED)
                        .dataType(DataType.DOUBLE)
                        .expressionContent("calc_delta_seq(numbers, base)")
                        .declaredValueShape(ValueShape.SEQUENCE)
                        .defaultValue(-1.0)
                        .outputPolicy(OutputPolicy.OUTPUT)
                        .build());
        RuntimeRun run = execute(
                registry,
                definitions,
                ExecutionEnvironment.OFFLINE,
                ExecutionContext.offlineBatch(
                        "native-batch",
                        List.of(
                                Map.of("numbers", List.of(1.0), "base", 5.0),
                                Map.of("numbers", List.of(2.0), "base", Double.NaN),
                                Map.of("numbers", List.of(3.0), "base", 7.0))),
                Set.of("result"));

        OfflineBatchValue values = (OfflineBatchValue) run.result().feature("result");
        assertEquals(
                Arrays.asList(List.of(4.0), List.of(-1.0), List.of(4.0)),
                values.values());
        assertEquals(1, operatorState(run, "calc_delta_seq").operatorFailureCount());
        assertEquals(1, featureOutputState(run, "result").fallbackCount());
    }

    private static List<FeatureDefinition> scalarDefinitions(
            EntityScope scope,
            String expression,
            Object defaultValue) {
        return List.of(
                FeatureDefinition.builder()
                        .name("score")
                        .role(FeatureRole.RAW)
                        .dataType(DataType.DOUBLE)
                        .addEntityScope(scope)
                        .sourceBinding("score")
                        .build(),
                FeatureDefinition.builder()
                        .name("result")
                        .role(FeatureRole.DERIVED)
                        .dataType(DataType.INT)
                        .expressionContent(expression)
                        .defaultValue(defaultValue)
                        .outputPolicy(OutputPolicy.OUTPUT)
                        .build());
    }

    private static RuntimeRun execute(
            OperatorRegistry registry,
            List<FeatureDefinition> definitions,
            ExecutionEnvironment environment,
            ExecutionContext context,
            Set<String> targets) {
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry)
                .build(definitions, targets);
        PhysicalPlan plan = new PhysicalPlanner(registry, new PhysicalRewriteRegistry()).plan(
                new LogicalDagOptimizer(registry).analyze(dag),
                environment,
                "batch-fallback-plan");
        ExecutionResult result = new DagRuntime(registry).execute(plan, context);
        return new RuntimeRun(plan, result);
    }

    private static RuntimeNodeState featureOutputState(RuntimeRun run, String featureName) {
        PhysicalNode node = run.plan().nodes().stream()
                .filter(candidate -> candidate.executorType() == ExecutorType.FEATURE_OUTPUT)
                .filter(candidate -> featureName.equals(candidate.executorConfig().get("featureName")))
                .findFirst()
                .orElseThrow();
        return run.result().nodeStates().get(node.physicalNodeId());
    }

    private static RuntimeNodeState operatorState(RuntimeRun run, String operatorName) {
        PhysicalNode node = run.plan().nodes().stream()
                .filter(candidate -> candidate.executorType() == ExecutorType.GENERIC_OPERATOR)
                .filter(candidate -> operatorName.equals(candidate.executorConfig().get("operatorName")))
                .findFirst()
                .orElseThrow();
        return run.result().nodeStates().get(node.physicalNodeId());
    }

    private record RuntimeRun(PhysicalPlan plan, ExecutionResult result) {
    }

    private static final class CountingPlusTenOperator implements OperatorDefinition {
        private final AtomicInteger invocationCount = new AtomicInteger();

        int invocationCount() {
            return invocationCount.get();
        }

        @Override public String name() { return "count_plus_ten"; }
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
            invocationCount.incrementAndGet();
            return ((Number) arguments.get(0)).intValue() + 10;
        }
    }
}
