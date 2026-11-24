package com.example.featuredag.runtime;

import com.example.featuredag.config.FeatureConfigLoader;
import com.example.featuredag.config.FeatureConfigMapper;
import com.example.featuredag.config.MappedFeatureSet;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.physical.CachePolicy;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.physical.ExecutionMode;
import com.example.featuredag.physical.ExecutionStage;
import com.example.featuredag.physical.ExecutorType;
import com.example.featuredag.physical.MaterializationPolicy;
import com.example.featuredag.physical.PhysicalNode;
import com.example.featuredag.physical.PhysicalPlan;
import com.example.featuredag.physical.PhysicalPlanner;
import com.example.featuredag.planning.LogicalDagOptimizer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 核心运行时按计划序号读写槽位与节点状态后，公开的 resultSlots() / nodeStates() / state(id)
 * 必须继续是实时视图：执行前预置的值、扩展执行器在执行期间的直接读写、非计划内的键都保持原有语义。
 */
public class OrdinalExecutionStorageTest {
    private static final String CONFIG = """
            {"feature_set_name":"ordinal-storage","version":"1","features":[
              {"name":"x","raw_name":"x","definition_type":"BASE","type":"INT","value_shape":"SEQUENCE","entity_scopes":["USER"]},
              {"name":"t","raw_name":"t","definition_type":"BASE","type":"INT","value_shape":"SCALAR","entity_scopes":["ITEM"]},
              {"name":"picked","definition_type":"DERIVED","type":"INT","value_shape":"SEQUENCE","expression":"slice_by_indices(x, find_indices(x, t))","output_policy":"OUTPUT"},
              {"name":"count","definition_type":"DERIVED","type":"INT","value_shape":"SCALAR","expression":"count_distinct(x)","output_policy":"OUTPUT"}
            ]}
            """;

    private static final String COUNT_ONLY_CONFIG = """
            {"feature_set_name":"ordinal-storage-count","version":"1","features":[
              {"name":"x","raw_name":"x","definition_type":"BASE","type":"INT","value_shape":"SEQUENCE","entity_scopes":["USER"]},
              {"name":"count","definition_type":"DERIVED","type":"INT","value_shape":"SCALAR","expression":"count_distinct(x)","output_policy":"OUTPUT"}
            ]}
            """;

    private final OperatorRegistry registry = OperatorRegistry.standard();
    private final DagRuntime runtime = new DagRuntime(registry);

    @Test
    public void publicViewsExposeExactlyWhatTheRuntimeStoredByOrdinal() {
        PhysicalPlan plan = planned(CONFIG, ExecutionEnvironment.ONLINE);
        ExecutionContext context = onlineRequest("views", List.of(1, 2, 1), 1);

        ExecutionResult result = runtime.execute(plan, context);

        for (PhysicalNode node : plan.nodes()) {
            RuntimeNodeState state = context.nodeStates().get(node.physicalNodeId());
            assertNotNull(node.physicalNodeId(), state);
            assertEquals(node.physicalNodeId(), state.physicalNodeId());
            assertEquals(ExecutionStatus.SUCCESS, state.status());
            // 同一个槽位按名称读取与按序号写入的是同一个对象；state(id) 返回运行时使用的那个实例。
            assertSame(state.resultHandle(), context.resultSlots().get(node.outputSlot()));
            assertSame(state, context.state(node.physicalNodeId()));
            assertTrue(context.resultSlots().containsKey(node.outputSlot()));
            assertTrue(context.nodeStates().containsKey(node.physicalNodeId()));
        }
        assertEquals(
                plan.nodes().stream().map(PhysicalNode::physicalNodeId).toList(),
                new ArrayList<>(context.nodeStates().keySet()));
        assertEquals(plan.nodes().size(), context.nodeStates().size());
        assertEquals(plan.nodes().size(), context.resultSlots().size());
        plan.outputFeatureSlots().forEach((feature, slot) ->
                assertSame(context.resultSlots().get(slot), result.featureOutputs().get(feature)));
        // t 是 ITEM 域，picked 按候选展开：一个候选，值为序列 [1, 1]。
        assertEquals(List.of(List.of(1, 1)), ((CandidateVectorValue) result.feature("picked")).values());
        assertEquals(2, ((ScalarValue) result.feature("count")).value());
    }

    @Test
    public void extensionExecutorsMayWriteAndReadSlotsThroughThePublicMapDuringExecution() {
        PhysicalExecutorRegistry executors = new PhysicalExecutorRegistry()
                .register("writer", (node, context, state) -> {
                    // 不经返回值，直接向另一个计划内声明的槽位写入。
                    context.resultSlots().put("slot:extra", new ScalarValue(7));
                    return new ScalarValue(1);
                })
                .register("reader", (node, context, state) -> context.resultSlots().get("slot:extra"));
        PhysicalPlan plan = specializedPlan(List.of(
                specialized("physical:writer", "writer", List.of(), "slot:writer"),
                specialized("physical:reader", "reader", List.of("slot:extra"), "slot:reader")));
        ExecutionContext context = onlineRequest("extension", List.of(1), 1);

        ExecutionResult result = new DagRuntime(registry, executors).execute(plan, context);

        assertEquals(7, ((ScalarValue) result.feature("result")).value());
        assertEquals(1, ((ScalarValue) context.resultSlots().get("slot:writer")).value());
        assertEquals(7, ((ScalarValue) context.resultSlots().get("slot:extra")).value());
        assertEquals(Set.of("slot:extra", "slot:reader", "slot:writer"), context.resultSlots().keySet());
    }

    @Test
    public void valuesPlacedBeforeExecutionAreMigratedIntoTheBoundLayout() {
        ScalarValue preset = new ScalarValue(42);
        PhysicalPlan plan = specializedPlan(List.of(
                specialized("physical:specialized", "echo", List.of("slot:input"), "slot:output")));
        ExecutionContext context = onlineRequest("preset", List.of(1), 1);
        Map<String, ValueHandle> heldView = context.resultSlots();
        heldView.put("slot:input", preset);
        RuntimeNodeState presetState = context.state("physical:specialized");
        presetState.setFallbackUsed(true);

        ExecutionResult result = new DagRuntime(registry, new PhysicalExecutorRegistry()
                .register("echo", (node, ctx, state) -> ctx.resultSlots().get("slot:input")))
                .execute(plan, context);

        assertSame(preset, result.feature("result"));
        // 绑定前取得的状态对象与 Map 视图在绑定后仍然有效，运行时继续使用同一个状态实例。
        assertSame(presetState, context.nodeStates().get("physical:specialized"));
        assertTrue(presetState.fallbackUsed());
        assertEquals(ExecutionStatus.SUCCESS, presetState.status());
        assertTrue(heldView.containsKey("slot:output"));
        assertSame(preset, heldView.get("slot:input"));
    }

    @Test
    public void keysOutsideThePlanSurviveBindingAndIterationStaysConsistent() {
        PhysicalPlan plan = planned(CONFIG, ExecutionEnvironment.ONLINE);
        ExecutionContext context = onlineRequest("overflow", List.of(1, 2, 1), 2);
        ScalarValue custom = new ScalarValue(9);
        context.resultSlots().put("custom-slot", custom);
        RuntimeNodeState customState = context.state("custom-node");

        runtime.execute(plan, context);

        assertSame(custom, context.resultSlots().get("custom-slot"));
        assertSame(customState, context.nodeStates().get("custom-node"));
        assertEquals(plan.nodes().size() + 1, context.resultSlots().size());
        assertEquals(plan.nodes().size() + 1, context.nodeStates().size());
        Map<String, ValueHandle> copied = new LinkedHashMap<>(context.resultSlots());
        assertEquals(context.resultSlots(), copied);
        assertEquals(context.resultSlots().hashCode(), copied.hashCode());
        assertEquals(context.nodeStates(), new LinkedHashMap<>(context.nodeStates()));
        // 视图之外对计划内槽位的修改同样生效。
        context.resultSlots().remove("custom-slot");
        assertNull(context.resultSlots().get("custom-slot"));
        assertEquals(plan.nodes().size(), context.resultSlots().size());
        // 视图的 put 与 LinkedHashMap 一样返回被覆盖的旧值。
        assertNull(context.resultSlots().put("custom-slot", custom));
        assertSame(custom, context.resultSlots().put("custom-slot", custom));
    }

    @Test
    public void releasedIntermediatesDisappearFromBothTheOrdinalAndThePublicPath() {
        PhysicalPlan plan = planned(CONFIG, ExecutionEnvironment.OFFLINE);
        ExecutionContext context = ExecutionContext.offlineBatch("release", List.of(
                Map.of("x", List.of(1, 2, 1), "t", 1), Map.of("x", List.of(3, 3), "t", 3)));

        ExecutionResult result = runtime.execute(plan, context, false, true);

        for (int index = 0; index < plan.nodes().size(); index++) {
            for (PhysicalPlan.SlotRelease release : plan.releasesAfterNode(index)) {
                assertNull(release.slot(), context.resultSlots().get(release.slot()));
                assertFalse(context.resultSlots().containsKey(release.slot()));
                assertNull(context.nodeStates().get(release.physicalNodeId()).resultHandle());
            }
        }
        assertEquals(Set.copyOf(plan.outputFeatureSlots().values()), context.resultSlots().keySet());
        // 切片结果是原序列里的元素，而不是下标。
        assertEquals(List.of(1, 1), ((OfflineBatchValue) result.feature("picked")).values().get(0));
        assertEquals(List.of(3, 3), ((OfflineBatchValue) result.feature("picked")).values().get(1));
    }

    @Test
    public void diagnosticsFreeExecutionSkipsSnapshotsAndTimingButKeepsStatus() {
        PhysicalPlan plan = planned(CONFIG, ExecutionEnvironment.ONLINE);
        ExecutionContext fullContext = onlineRequest("full", List.of(1, 2, 1), 1);
        ExecutionContext lightContext = onlineRequest("light", List.of(1, 2, 1), 1);

        ExecutionResult full = runtime.execute(plan, fullContext);
        ExecutionResult light = runtime.execute(plan, lightContext, true, false);

        assertEquals(full.featureOutputs().keySet(), light.featureOutputs().keySet());
        assertEquals(((CandidateVectorValue) full.feature("picked")).values(),
                ((CandidateVectorValue) light.feature("picked")).values());
        assertEquals(((ScalarValue) full.feature("count")).value(),
                ((ScalarValue) light.feature("count")).value());
        // 公共低层入口仍然给出独立的快照；免诊断入口给出上下文中活动状态的同一实例。
        for (PhysicalNode node : plan.nodes()) {
            assertNotSame(fullContext.state(node.physicalNodeId()), full.nodeStates().get(node.physicalNodeId()));
            assertSame(lightContext.state(node.physicalNodeId()), light.nodeStates().get(node.physicalNodeId()));
            assertEquals(ExecutionStatus.SUCCESS, light.nodeStates().get(node.physicalNodeId()).status());
            assertEquals(0L, light.nodeStates().get(node.physicalNodeId()).durationNanos());
        }
        assertSame(light.nodeStates(), light.nodeStates());
        assertEquals(plan.nodes().size(), light.nodeStates().size());
        assertEquals(Map.of(), light.cacheStats());
    }

    @Test
    public void executingAnotherPlanOnTheSameContextRebindsTheLayout() {
        PhysicalPlan first = planned(CONFIG, ExecutionEnvironment.ONLINE);
        PhysicalPlan second = planned(COUNT_ONLY_CONFIG, ExecutionEnvironment.ONLINE);
        ExecutionContext context = onlineRequest("rebind", List.of(4, 4, 5), 5);

        runtime.execute(first, context);
        ExecutionResult rebound = runtime.execute(second, context);

        assertEquals(2, ((ScalarValue) rebound.feature("count")).value());
        for (PhysicalNode node : second.nodes()) {
            RuntimeNodeState state = context.state(node.physicalNodeId());
            assertSame(state, context.nodeStates().get(node.physicalNodeId()));
            assertSame(state.resultHandle(), context.resultSlots().get(node.outputSlot()));
            assertEquals(ExecutionStatus.SUCCESS, state.status());
        }
        assertSame(rebound.feature("count"), context.resultSlots().get(second.outputFeatureSlots().get("count")));
    }

    @Test
    public void failedNodesStayVisibleForAttributionWithoutDiagnostics() {
        PhysicalExecutorRegistry executors = new PhysicalExecutorRegistry()
                .register("failing", (node, context, state) -> {
                    throw new IllegalStateException("boom");
                });
        PhysicalPlan plan = specializedPlan(List.of(
                specialized("physical:failing", "failing", List.of(), "slot:never")));
        ExecutionContext context = onlineRequest("failure", List.of(1), 1);

        IllegalStateException error = org.junit.Assert.assertThrows(IllegalStateException.class,
                () -> new DagRuntime(registry, executors).execute(plan, context, true, false));

        assertEquals("boom", error.getMessage());
        RuntimeNodeState state = context.nodeStates().get("physical:failing");
        assertEquals(ExecutionStatus.FAILED, state.status());
        assertSame(error, state.error());
        assertNull(context.resultSlots().get("slot:never"));
    }

    private static PhysicalNode specialized(
            String physicalNodeId, String executorId, List<String> inputs, String outputSlot) {
        return new PhysicalNode(
                physicalNodeId, List.of("logical:" + physicalNodeId), ExecutorType.SPECIALIZED, executorId,
                ExecutionStage.CANDIDATE_BATCH, ExecutionMode.BATCH, ValueShape.SCALAR,
                inputs, outputSlot, CachePolicy.NONE, MaterializationPolicy.LAZY, Map.of());
    }

    private static PhysicalPlan specializedPlan(List<PhysicalNode> nodes) {
        return new PhysicalPlan("ordinal-specialized", ExecutionEnvironment.ONLINE, nodes,
                Map.of("result", nodes.get(nodes.size() - 1).outputSlot()));
    }

    private PhysicalPlan planned(String config, ExecutionEnvironment environment) {
        MappedFeatureSet mapped = FeatureConfigMapper.map(FeatureConfigLoader.load(config), Set.of(), Map.of());
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry)
                .build(mapped.definitions(), mapped.targetFeatures());
        return new PhysicalPlanner(registry).plan(
                new LogicalDagOptimizer(registry).analyze(dag), environment, "ordinal-storage");
    }

    private static ExecutionContext onlineRequest(String executionId, List<?> sequence, Object target) {
        Map<String, Object> shared = new LinkedHashMap<>();
        shared.put("x", sequence);
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("t", target);
        return ExecutionContext.onlineRequest(executionId, shared, List.of(candidate));
    }
}
