package com.example.featuredag.planning;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.FeatureDefinition;
import com.example.featuredag.definition.FeatureRole;
import com.example.featuredag.definition.OutputPolicy;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.operator.BatchKernelKind;
import com.example.featuredag.operator.BatchOperatorCall;
import com.example.featuredag.operator.BatchOperatorKernel;
import com.example.featuredag.operator.BatchOperatorResult;
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
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class FailureRecoveryPlanningTest {
    @Test
    public void nonNullDerivedDefaultMarksItsWholeProducerPath() {
        OperatorRegistry registry = OperatorRegistry.standard();
        LogicalDag dag = buildToIntDag(registry, true);
        OptimizedLogicalPlan optimized = new LogicalDagOptimizer(registry).analyze(dag);
        String outputId = dag.featureOutput("result").nodeId();
        String operatorId = dag.featureOutput("result").producerNodeId();

        assertTrue(optimized.metadata().node(outputId).failureRecoveryRequired());
        assertTrue(optimized.metadata().node(operatorId).failureRecoveryRequired());
        assertTrue(optimized.metadata().node(dag.node(operatorId).inputs().get(0).nodeId())
                .failureRecoveryRequired());
    }

    @Test
    public void missingDerivedDefaultLeavesProducerPathFailFast() {
        OperatorRegistry registry = OperatorRegistry.standard();
        LogicalDag dag = buildToIntDag(registry, false);
        OptimizedLogicalPlan optimized = new LogicalDagOptimizer(registry).analyze(dag);
        String operatorId = dag.featureOutput("result").producerNodeId();

        assertFalse(optimized.metadata().node(operatorId).failureRecoveryRequired());
    }

    @Test
    public void sharedProducerRequiresRecoveryWhenAnyOwningFeatureHasDefault() {
        OperatorRegistry registry = OperatorRegistry.standard();
        List<FeatureDefinition> definitions = List.of(
                FeatureDefinition.raw("score", DataType.DOUBLE, EntityScope.USER, null),
                derivedToInt("recovered", -1),
                derivedToInt("fail_fast", null));
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry)
                .build(definitions, Set.of("recovered", "fail_fast"));
        String sharedProducerId = dag.featureOutput("recovered").producerNodeId();
        assertEquals(sharedProducerId, dag.featureOutput("fail_fast").producerNodeId());

        OptimizedLogicalPlan optimized = new LogicalDagOptimizer(registry).analyze(dag);

        assertTrue(optimized.metadata().node(sharedProducerId).failureRecoveryRequired());
    }

    @Test
    public void legacyNativeKernelFallsBackToScalarOnlyOnRecoveryPath() {
        OperatorRegistry registry = new OperatorRegistry().register(new LegacyNativeOperator());

        PhysicalNode recovered = operatorNode(physicalPlan(registry, true));
        PhysicalNode failFast = operatorNode(physicalPlan(registry, false));

        assertEquals(
                BatchKernelKind.SCALAR_ADAPTER.name(),
                recovered.executorConfig().get("batchKernelKind"));
        assertEquals(
                BatchKernelKind.NATIVE.name(),
                failFast.executorConfig().get("batchKernelKind"));
    }

    private static LogicalDag buildToIntDag(OperatorRegistry registry, boolean withDefault) {
        return new LogicalDagBuilder(new ExpressionParser(), registry).build(
                List.of(
                        FeatureDefinition.raw("score", DataType.DOUBLE, EntityScope.USER, null),
                        derivedToInt("result", withDefault ? -1 : null)),
                Set.of("result"));
    }

    private static FeatureDefinition derivedToInt(String name, Object defaultValue) {
        return FeatureDefinition.builder()
                .name(name)
                .role(FeatureRole.DERIVED)
                .dataType(DataType.INT)
                .expressionContent("to_int(score)")
                .defaultValue(defaultValue)
                .outputPolicy(OutputPolicy.OUTPUT)
                .build();
    }

    private static PhysicalPlan physicalPlan(OperatorRegistry registry, boolean withDefault) {
        List<FeatureDefinition> definitions = List.of(
                FeatureDefinition.raw("score", DataType.DOUBLE, EntityScope.USER, null),
                FeatureDefinition.builder()
                        .name("result")
                        .role(FeatureRole.DERIVED)
                        .dataType(DataType.INT)
                        .expressionContent("legacy_native(score)")
                        .defaultValue(withDefault ? -1 : null)
                        .outputPolicy(OutputPolicy.OUTPUT)
                        .build());
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry)
                .build(definitions, Set.of("result"));
        OptimizedLogicalPlan optimized = new LogicalDagOptimizer(registry).analyze(dag);
        return new PhysicalPlanner(registry, new PhysicalRewriteRegistry())
                .plan(optimized, ExecutionEnvironment.OFFLINE, "recovery-plan");
    }

    private static PhysicalNode operatorNode(PhysicalPlan plan) {
        for (PhysicalNode node : plan.nodes()) {
            if (node.executorType() == ExecutorType.GENERIC_OPERATOR) return node;
        }
        throw new AssertionError("Missing generic operator node");
    }

    private static final class LegacyNativeOperator
            implements OperatorDefinition, BatchOperatorKernel {
        @Override public String name() { return "legacy_native"; }
        @Override public int minArguments() { return 1; }
        @Override public int maxArguments() { return 1; }
        @Override public boolean deterministic() { return true; }
        @Override public boolean supportsSequenceView() { return false; }
        @Override public boolean sideEffectFree() { return true; }

        @Override
        public OperatorInference infer(List<OperatorInputMetadata> inputs) {
            return new OperatorInference(DataType.INT, inputs.get(0).entityScopes(), ValueShape.SCALAR);
        }

        @Override
        public Object evaluate(List<Object> arguments) {
            return ((Number) arguments.get(0)).intValue();
        }

        @Override
        public BatchOperatorResult evaluateBatch(BatchOperatorCall call) {
            throw new UnsupportedOperationException("not exercised by planning test");
        }
    }
}
