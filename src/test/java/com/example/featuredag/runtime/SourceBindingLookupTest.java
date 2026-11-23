package com.example.featuredag.runtime;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.FeatureDefinition;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.physical.ExecutorType;
import com.example.featuredag.physical.PhysicalNode;
import com.example.featuredag.physical.PhysicalPlan;
import com.example.featuredag.physical.PhysicalPlanner;
import com.example.featuredag.planning.LogicalDagOptimizer;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 源值绑定只做一次 Map 查找，但必须继续区分三种输入：
 * 有值、缺失（用 dft 兜底）、显式 null（原样绑定，交由特征输出边界处理）。
 */
public class SourceBindingLookupTest {

    @Test
    public void onlineRequestBindsPresentMissingAndExplicitNullPerCandidate() {
        PhysicalPlan plan = plan(ExecutionEnvironment.ONLINE, FeatureDefinition.raw("tag", DataType.STRING, EntityScope.ITEM, "fallback"));
        List<Map<String, Object>> candidates = List.of(
                row("a"), Map.of(), row(null));

        ExecutionContext context = ExecutionContext.onlineRequest("single", Map.of(), candidates);
        ExecutionResult result = new DagRuntime(OperatorRegistry.standard()).execute(plan, context);

        assertEquals(Arrays.asList("a", "fallback", null), sourceValues(result, plan));
    }

    @Test
    public void onlineBatchBindsItemSourcesPerCandidate() {
        PhysicalPlan plan = plan(ExecutionEnvironment.ONLINE, FeatureDefinition.raw("tag", DataType.STRING, EntityScope.ITEM, "fallback"));
        List<Map<String, Object>> candidates = List.of(
                row("a"), Map.of(), row(null));

        ExecutionContext context = ExecutionContext.onlineBatch(
                "batch", List.of("g1"), List.of(Map.of()), List.of(candidates));
        ExecutionResult result = new DagRuntime(OperatorRegistry.standard()).execute(plan, context);

        assertEquals(Arrays.asList("a", "fallback", null), sourceValues(result, plan));
    }

    @Test
    public void onlineBatchBindsGroupSourcesPerGroup() {
        PhysicalPlan plan = plan(ExecutionEnvironment.ONLINE, FeatureDefinition.raw("tag", DataType.STRING, EntityScope.USER, "fallback"));
        List<Map<String, Object>> groups = List.of(row("a"), Map.of(), row(null));

        ExecutionContext context = ExecutionContext.onlineBatch(
                "batch", List.of("g1", "g2", "g3"), groups,
                List.of(List.of(row("x")), List.of(row("y")), List.of(row("z"))));
        ExecutionResult result = new DagRuntime(OperatorRegistry.standard()).execute(plan, context);

        assertEquals(Arrays.asList("a", "fallback", null), sourceValues(result, plan));
    }

    @Test
    public void sharedSourceKeepsExplicitNullAndFallsBackOnlyWhenMissing() {
        PhysicalPlan present = plan(ExecutionEnvironment.OFFLINE, FeatureDefinition.raw("tag", DataType.STRING, EntityScope.USER, "fallback"));
        PhysicalNode source = sourceNode(present);

        assertEquals("a", sourceValues(execute(present, row("a")), present).get(0));
        assertEquals("fallback", sourceValues(execute(present, Map.of()), present).get(0));
        assertNull(sourceValues(execute(present, row(null)), present).get(0));
        assertEquals(ExecutorType.SOURCE_BINDING, source.executorType());
    }

    private static ExecutionResult execute(PhysicalPlan plan, Map<String, Object> row) {
        return new DagRuntime(OperatorRegistry.standard())
                .execute(plan, ExecutionContext.offlineRow("shared", row));
    }

    private static PhysicalPlan plan(ExecutionEnvironment environment, FeatureDefinition tag) {
        OperatorRegistry registry = OperatorRegistry.standard();
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry)
                .build(List.of(tag), Set.of(tag.name()));
        return new PhysicalPlanner(registry).plan(
                new LogicalDagOptimizer(registry).analyze(dag),
                environment,
                "source-binding-lookup");
    }

    private static PhysicalNode sourceNode(PhysicalPlan plan) {
        return plan.nodes().stream()
                .filter(node -> node.executorType() == ExecutorType.SOURCE_BINDING)
                .findFirst()
                .orElseThrow();
    }

    private static List<Object> sourceValues(ExecutionResult result, PhysicalPlan plan) {
        ValueHandle handle = result.nodeStates().get(sourceNode(plan).physicalNodeId()).resultHandle();
        if (handle instanceof CandidateVectorValue vector) return vector.values();
        if (handle instanceof CandidateBatchValue batch) return batch.values();
        if (handle instanceof RequestBatchValue batch) return batch.values();
        return Collections.singletonList(((ScalarValue) handle).value());
    }

    private static Map<String, Object> row(String value) {
        return value == null
                ? Collections.singletonMap("tag", null)
                : Map.of("tag", value);
    }

}
