package com.example.featuredag.physical;

import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.logical.FeatureOutputNode;
import com.example.featuredag.logical.LiteralNode;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalNode;
import com.example.featuredag.logical.OperatorNode;
import com.example.featuredag.logical.SourceNode;
import com.example.featuredag.logical.ValueShape;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.physical.rewrite.PhysicalRewrite;
import com.example.featuredag.physical.rewrite.PhysicalRewriteRegistry;
import com.example.featuredag.planning.NodePlanningMetadata;
import com.example.featuredag.planning.OptimizedLogicalPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 物理层（L2）转换器：按逻辑拓扑序把逻辑节点映射为物理节点与输出槽（C9）。
 * 专用融合由注册的 PhysicalRewriteRule 描述；本类不按业务算子名称做判断（C10）。
 */
public final class PhysicalPlanner {
    private static final long CANDIDATE_CACHE_COST_THRESHOLD = 100L;

    private final OperatorRegistry operatorRegistry;
    private final PhysicalRewriteRegistry rewriteRegistry;

    public PhysicalPlanner() {
        this(OperatorRegistry.standard(), PhysicalRewriteRegistry.standard());
    }

    public PhysicalPlanner(OperatorRegistry operatorRegistry) {
        this(operatorRegistry, PhysicalRewriteRegistry.standard());
    }

    public PhysicalPlanner(
            OperatorRegistry operatorRegistry,
            PhysicalRewriteRegistry rewriteRegistry) {
        this.operatorRegistry = Objects.requireNonNull(operatorRegistry, "operatorRegistry");
        this.rewriteRegistry = Objects.requireNonNull(rewriteRegistry, "rewriteRegistry");
    }

    public PhysicalPlan plan(OptimizedLogicalPlan optimized, ExecutionEnvironment environment, String planId) {
        LogicalDag dag = optimized.dag();
        Map<String, PhysicalRewrite> rewrites = rewriteRegistry.select(
                optimized, environment, operatorRegistry);
        Set<String> skippedLogicalNodes = new LinkedHashSet<>();
        for (PhysicalRewrite rewrite : rewrites.values()) {
            for (String consumedNodeId : rewrite.consumedNodeIds()) {
                if (!consumedNodeId.equals(rewrite.rootNodeId())) {
                    skippedLogicalNodes.add(consumedNodeId);
                }
            }
        }

        // C9：按逻辑拓扑序转换；融合节点共享一个输出槽，被安全消费的中间节点不再单独物化。
        Map<String, String> logicalSlots = new HashMap<>();
        List<PhysicalNode> physicalNodes = new ArrayList<>();
        Map<String, String> outputFeatureSlots = new LinkedHashMap<>();
        int sequence = 0;

        for (String logicalNodeId : dag.topologicalOrder()) {
            if (skippedLogicalNodes.contains(logicalNodeId)) continue;
            LogicalNode logicalNode = dag.node(logicalNodeId);
            PhysicalRewrite rewrite = rewrites.get(logicalNodeId);
            String outputSlot = "slot:" + (++sequence);
            PhysicalNode physicalNode;
            if (rewrite != null) {
                List<String> inputSlots = rewrite.externalInputNodeIds().stream()
                        .map(inputNodeId -> requireSlot(logicalSlots, inputNodeId))
                        .toList();
                Map<String, Object> config = new LinkedHashMap<>(rewrite.executorConfig());
                config.put("rewriteRuleId", rewrite.ruleId());
                physicalNode = new PhysicalNode(
                        "physical:" + sequence + ":specialized",
                        rewrite.consumedNodeIds(),
                        ExecutorType.SPECIALIZED,
                        rewrite.executorId(),
                        rewrite.executionStage(),
                        rewrite.executionMode(),
                        logicalNode.valueShape(),
                        inputSlots,
                        outputSlot,
                        rewrite.cachePolicy(),
                        rewrite.materializationPolicy(),
                        config);
            } else {
                List<String> inputSlots = logicalNode.inputs().stream()
                        .map(input -> requireSlot(logicalSlots, input.nodeId()))
                        .toList();
                physicalNode = createGenericPhysicalNode(
                        dag,
                        optimized.metadata().node(logicalNodeId),
                        logicalNode,
                        environment,
                        sequence,
                        inputSlots,
                        outputSlot);
            }
            logicalSlots.put(logicalNodeId, outputSlot);
            physicalNodes.add(physicalNode);

            if (logicalNode instanceof FeatureOutputNode output
                    && dag.rootNodeIds().contains(output.nodeId())) {
                outputFeatureSlots.put(output.featureName(), physicalNode.outputSlot());
            }
        }

        if (outputFeatureSlots.size() != dag.featureOutputNodeIds().entrySet().stream()
                .filter(entry -> dag.rootNodeIds().contains(entry.getValue()))
                .count()) {
            throw new IllegalStateException("Not every logical feature root has a physical output slot");
        }
        // C9/C10：物理计划固化执行器、阶段、模式、缓存和物化策略，运行时不得临时改写。
        return new PhysicalPlan(planId, environment, physicalNodes, outputFeatureSlots);
    }

    private PhysicalNode createGenericPhysicalNode(
            LogicalDag dag,
            NodePlanningMetadata metadata,
            LogicalNode node,
            ExecutionEnvironment environment,
            int sequence,
            List<String> inputSlots,
            String outputSlot) {
        ExecutorType executorType;
        String executorId;
        Map<String, Object> config = new LinkedHashMap<>();
        if (node instanceof SourceNode source) {
            executorType = ExecutorType.SOURCE_BINDING;
            executorId = PhysicalExecutorIds.SOURCE_BINDING;
            config.put("featureName", source.featureName());
            config.put("sourceBinding", source.sourceBinding());
            config.put("defaultValue", source.defaultValue());
            config.put("entityScopes", source.entityScopes().stream().map(Enum::name).toList());
        } else if (node instanceof LiteralNode literal) {
            executorType = ExecutorType.LITERAL;
            executorId = PhysicalExecutorIds.LITERAL;
            config.put("value", literal.value());
        } else if (node instanceof OperatorNode operator) {
            executorType = ExecutorType.GENERIC_OPERATOR;
            executorId = PhysicalExecutorIds.GENERIC_OPERATOR;
            config.put("operatorName", operator.operatorName());
        } else if (node instanceof FeatureOutputNode output) {
            executorType = ExecutorType.FEATURE_OUTPUT;
            executorId = PhysicalExecutorIds.FEATURE_OUTPUT;
            config.put("featureName", output.featureName());
            config.put("isRoot", dag.rootNodeIds().contains(output.nodeId()));
        } else {
            throw new IllegalArgumentException("Unsupported logical node: " + node.nodeType());
        }

        ExecutionStage stage = stageFor(node, environment);
        ExecutionMode mode = environment == ExecutionEnvironment.OFFLINE
                ? ExecutionMode.BATCH
                : (stage == ExecutionStage.REQUEST_SHARED ? ExecutionMode.REQUEST : ExecutionMode.BATCH);
        CachePolicy cache = cacheFor(node, metadata, environment, stage);
        MaterializationPolicy materialization = materializationFor(node);

        return new PhysicalNode(
                "physical:" + sequence + ":" + node.nodeType().name().toLowerCase(),
                List.of(node.nodeId()),
                executorType,
                executorId,
                stage,
                mode,
                node.valueShape(),
                inputSlots,
                outputSlot,
                cache,
                materialization,
                config);
    }

    /**
     * C10：OFFLINE 固定为离线批；ONLINE 的 ITEM 依赖节点走候选批，其余走请求共享阶段。
     */
    private static ExecutionStage stageFor(LogicalNode node, ExecutionEnvironment environment) {
        if (environment == ExecutionEnvironment.OFFLINE) return ExecutionStage.OFFLINE_BATCH;
        return node.entityScopes().contains(EntityScope.ITEM)
                ? ExecutionStage.CANDIDATE_BATCH
                : ExecutionStage.REQUEST_SHARED;
    }

    private static CachePolicy cacheFor(
            LogicalNode node,
            NodePlanningMetadata metadata,
            ExecutionEnvironment environment,
            ExecutionStage stage) {
        if (!metadata.cacheEligible()) return CachePolicy.NONE;
        if (environment == ExecutionEnvironment.ONLINE && stage == ExecutionStage.REQUEST_SHARED) {
            return CachePolicy.REQUEST;
        }
        if (environment == ExecutionEnvironment.ONLINE
                && stage == ExecutionStage.CANDIDATE_BATCH
                && node instanceof OperatorNode
                && metadata.estimatedCost() >= CANDIDATE_CACHE_COST_THRESHOLD) {
            return CachePolicy.CANDIDATE_KEY;
        }
        return CachePolicy.NONE;
    }

    private static MaterializationPolicy materializationFor(LogicalNode node) {
        if (node.valueShape() == ValueShape.SEQUENCE) return MaterializationPolicy.VIEW;
        return MaterializationPolicy.LAZY;
    }

    private static String requireSlot(Map<String, String> slots, String logicalNodeId) {
        String slot = slots.get(logicalNodeId);
        if (slot == null) throw new IllegalStateException("No physical slot for logical node: " + logicalNodeId);
        return slot;
    }
}
