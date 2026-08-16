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
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.physical.ExecutorType;
import com.example.featuredag.physical.PhysicalNode;
import com.example.featuredag.physical.PhysicalPlan;
import com.example.featuredag.physical.PhysicalPlanner;
import com.example.featuredag.planning.LogicalDagOptimizer;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 覆盖 C6 唯一放宽场景（声明 DOUBLE、推断 INT）下运行时边界定宽：
 * FEATURE_OUTPUT 节点必须把整型运行时载体统一转换为 Double，使对外产出的运行时类型
 * 与声明类型一致（此前 add/sub/mul/min/max 按输入操作数载体定宽，忽略了声明类型）。
 */
public class FeatureOutputDoubleWideningTest {

    @Test
    public void addWithAllIntOperandsWidensToDoubleWhenDeclaredDouble() {
        OperatorRegistry registry = OperatorRegistry.standard();
        List<FeatureDefinition> definitions = List.of(
                FeatureDefinition.raw("clicks", DataType.INT, EntityScope.USER, null),
                FeatureDefinition.raw("bonus", DataType.INT, EntityScope.USER, null),
                FeatureDefinition.derived(
                        "total", DataType.DOUBLE, "add(clicks, bonus)", OutputPolicy.OUTPUT));
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry)
                .build(definitions, Set.of("total"));
        PhysicalPlan plan = new PhysicalPlanner(registry).plan(
                new LogicalDagOptimizer(registry).analyze(dag),
                ExecutionEnvironment.OFFLINE,
                "add-widen-case");

        ExecutionResult result = new DagRuntime(registry).execute(
                plan,
                ExecutionContext.offlineRow("add-widen-case", Map.of("clicks", 3, "bonus", 4)));

        Object raw = result.feature("total").raw();
        assertTrue("expected Double, got " + raw.getClass(), raw instanceof Double);
        assertEquals(Double.valueOf(7.0), raw);
    }

    @Test
    public void minWinningIntegerLiteralWidensToDoubleWhenDeclaredDouble() {
        OperatorRegistry registry = OperatorRegistry.standard();
        List<FeatureDefinition> definitions = List.of(
                FeatureDefinition.raw("score", DataType.DOUBLE, EntityScope.USER, null),
                FeatureDefinition.derived(
                        "score_cap", DataType.DOUBLE, "min(score, 5)", OutputPolicy.OUTPUT));
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry)
                .build(definitions, Set.of("score_cap"));
        PhysicalPlan plan = new PhysicalPlanner(registry).plan(
                new LogicalDagOptimizer(registry).analyze(dag),
                ExecutionEnvironment.OFFLINE,
                "min-widen-case");

        // score = 7.0 >= 5，min 的胜出参数是整型字面量 5，验证边界仍定宽为 Double。
        ExecutionResult result = new DagRuntime(registry).execute(
                plan,
                ExecutionContext.offlineRow("min-widen-case", Map.of("score", 7.0)));

        Object raw = result.feature("score_cap").raw();
        assertTrue("expected Double, got " + raw.getClass(), raw instanceof Double);
        assertEquals(Double.valueOf(5.0), raw);
    }

    @Test
    public void intSequenceWidensEachElementWhenDeclaredDouble() {
        OperatorRegistry registry = OperatorRegistry.standard();
        List<FeatureDefinition> definitions = List.of(
                FeatureDefinition.builder()
                        .name("numbers")
                        .role(FeatureRole.RAW)
                        .dataType(DataType.INT)
                        .addEntityScope(EntityScope.USER)
                        .sourceBinding("numbers")
                        .declaredValueShape(ValueShape.SEQUENCE)
                        .build(),
                FeatureDefinition.builder()
                        .name("selected")
                        .role(FeatureRole.DERIVED)
                        .dataType(DataType.DOUBLE)
                        .expressionContent("slice_by_indices(numbers, [0, 2])")
                        .outputPolicy(OutputPolicy.OUTPUT)
                        .declaredValueShape(ValueShape.SEQUENCE)
                        .build());
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry)
                .build(definitions, Set.of("selected"));
        PhysicalPlan plan = new PhysicalPlanner(registry).plan(
                new LogicalDagOptimizer(registry).analyze(dag),
                ExecutionEnvironment.OFFLINE,
                "sequence-widen-case");

        ExecutionResult single = new DagRuntime(registry).execute(
                plan,
                ExecutionContext.offlineRow(
                        "sequence-widen-case", Map.of("numbers", List.of(1, 2, 3))));
        assertEquals(List.of(1.0, 3.0), single.feature("selected").raw());

        ExecutionResult batch = new DagRuntime(registry).execute(
                plan,
                ExecutionContext.offlineBatch(
                        "sequence-widen-batch-case",
                        List.of(
                                Map.of("numbers", List.of(1, 2, 3)),
                                Map.of("numbers", List.of(4, 5, 6)))));
        OfflineBatchValue values = (OfflineBatchValue) batch.feature("selected");
        assertEquals(List.of(1.0, 3.0), values.valueAt(0));
        assertEquals(List.of(4.0, 6.0), values.valueAt(1));
    }

    @Test
    public void alreadyDoubleBatchKeepsFeatureBoundaryZeroCopy() {
        OperatorRegistry registry = OperatorRegistry.standard();
        FeatureDefinition numbers = FeatureDefinition.builder()
                .name("numbers")
                .role(FeatureRole.RAW)
                .dataType(DataType.DOUBLE)
                .addEntityScope(EntityScope.USER)
                .sourceBinding("numbers")
                .declaredValueShape(ValueShape.SEQUENCE)
                .build();
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry)
                .build(List.of(numbers), Set.of("numbers"));
        PhysicalPlan plan = new PhysicalPlanner(registry).plan(
                new LogicalDagOptimizer(registry).analyze(dag),
                ExecutionEnvironment.OFFLINE,
                "double-zero-copy-case");
        ExecutionContext context = ExecutionContext.offlineBatch(
                "double-zero-copy-case",
                List.of(
                        Map.of("numbers", List.of(1.0, 2.0)),
                        Map.of("numbers", List.of(3.0, 4.0))));

        ExecutionResult result = new DagRuntime(registry).execute(plan, context);
        PhysicalNode source = plan.nodes().stream()
                .filter(node -> node.executorType() == ExecutorType.SOURCE_BINDING)
                .findFirst()
                .orElseThrow();

        assertSame(context.resultSlots().get(source.outputSlot()), result.feature("numbers"));
    }
}
