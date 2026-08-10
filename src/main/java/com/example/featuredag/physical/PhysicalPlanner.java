package com.example.featuredag.physical;

import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.logical.FeatureOutputNode;
import com.example.featuredag.logical.LiteralNode;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalNode;
import com.example.featuredag.logical.OperatorNode;
import com.example.featuredag.logical.SourceNode;
import com.example.featuredag.logical.ValueShape;
import com.example.featuredag.planning.CountExtractIndustryMatch;
import com.example.featuredag.planning.LogicalDagOptimizer;
import com.example.featuredag.planning.NodePlanningMetadata;
import com.example.featuredag.planning.OptimizedLogicalPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 物理层（L2）转换器：按逻辑拓扑序把每个逻辑节点映射为物理节点与输出槽（C9）。
 * 仅 ONLINE 环境允许节点融合（如 countIndustry 批量算子）；执行阶段、执行模式、
 * 缓存与物化策略全部由 ExecutionEnvironment 与节点特征在构建期推导（C10）。
 */
public final class PhysicalPlanner {
    private final LogicalDagOptimizer optimizer = new LogicalDagOptimizer();

    public PhysicalPlan plan(OptimizedLogicalPlan optimized, ExecutionEnvironment environment, String planId) {
        LogicalDag dag = optimized.dag();
        // C9：仅在 ONLINE 环境允许融合；被融合的 extract/中间节点将跳过单节点映射
        Map<String, CountExtractIndustryMatch> fusionByCountNode = environment == ExecutionEnvironment.ONLINE
                ? findOnlineFusionMatches(optimized) : Map.of();
        Set<String> skippedLogicalNodes = new LinkedHashSet<>();
        for (CountExtractIndustryMatch match : fusionByCountNode.values()) {
            skippedLogicalNodes.add(match.extractNodeId());
            skippedLogicalNodes.addAll(match.intermediateNodeIds());
        }

        // C9：物理转换入口——每个逻辑节点产出一个物理节点与输出槽（slot:N），保持逻辑拓扑序；
        // 融合匹配仅 ONLINE 存在，被融合的 extract/中间节点跳过单节点映射
        Map<String, String> logicalSlots = new HashMap<>();
        List<PhysicalNode> physicalNodes = new ArrayList<>();
        Map<String, String> outputFeatureSlots = new LinkedHashMap<>();
        int sequence = 0;

        // C9：保持逻辑拓扑序逐节点映射，每个逻辑节点恰好产出一个物理输出槽 slot:N
        for (String logicalNodeId : dag.topologicalOrder()) {
            if (skippedLogicalNodes.contains(logicalNodeId)) continue;
            LogicalNode logicalNode = dag.node(logicalNodeId);
            CountExtractIndustryMatch fusion = fusionByCountNode.get(logicalNodeId);
            PhysicalNode physicalNode;
            if (fusion != null) {
                OperatorNode extract = (OperatorNode) dag.node(fusion.extractNodeId());
                List<String> inputSlots = extract.inputs().stream()
                        .map(input -> requireSlot(logicalSlots, input.nodeId()))
                        .toList();
                // C9：融合节点把 extract + 中间节点 + count 合并为单个物理节点，共用一个输出槽
                String outputSlot = "slot:" + (++sequence);
                List<String> fusedLogicalNodeIds = new ArrayList<>();
                fusedLogicalNodeIds.add(fusion.extractNodeId());
                fusedLogicalNodeIds.addAll(fusion.intermediateNodeIds());
                fusedLogicalNodeIds.add(logicalNodeId);
                physicalNode = new PhysicalNode(
                        "physical:countIndustry:" + sequence,
                        fusedLogicalNodeIds,
                        ExecutorType.COUNT_INDUSTRY_BATCH,
                        ExecutionStage.CANDIDATE_BATCH,
                        ExecutionMode.CANDIDATE_KEY,
                        logicalNode.valueShape(),
                        inputSlots,
                        outputSlot,
                        CachePolicy.CANDIDATE_KEY,
                        MaterializationPolicy.LAZY,
                        Map.of(
                                "operatorName", "countIndustry",
                                "dedupKey", "item_industry",
                                "cacheKeySpec", "sequenceHandle+item_industry",
                                "useIndustryIndex", Boolean.TRUE));
                logicalSlots.put(logicalNodeId, outputSlot);
            } else {
                List<String> inputSlots = logicalNode.inputs().stream()
                        .map(input -> requireSlot(logicalSlots, input.nodeId()))
                        .toList();
                String outputSlot = "slot:" + (++sequence);
                physicalNode = createGenericPhysicalNode(
                        dag, logicalNode, environment, sequence, inputSlots, outputSlot);
                logicalSlots.put(logicalNodeId, outputSlot);
            }
            physicalNodes.add(physicalNode);

            if (logicalNode instanceof FeatureOutputNode output && dag.rootNodeIds().contains(output.nodeId())) {
                outputFeatureSlots.put(output.featureName(), physicalNode.outputSlot());
            }
        }

        // C9/C10：产物为不可变物理计划——节点序列、槽位、执行策略与输出特征槽位映射
        return new PhysicalPlan(planId, environment, physicalNodes, outputFeatureSlots);
    }

    private PhysicalNode createGenericPhysicalNode(
            LogicalDag dag,
            LogicalNode node,
            ExecutionEnvironment environment,
            int sequence,
            List<String> inputSlots,
            String outputSlot) {
        // C10：执行器类型按逻辑节点类型一对一映射，执行参数固化在 config 中
        ExecutorType executorType;
        Map<String, Object> config = new LinkedHashMap<>();
        if (node instanceof SourceNode source) {
            executorType = ExecutorType.SOURCE_BINDING;
            config.put("featureName", source.featureName());
            config.put("sourceBinding", source.sourceBinding());
            config.put("defaultValue", source.defaultValue());
            config.put("entityScopes", source.entityScopes().stream().map(Enum::name).toList());
        } else if (node instanceof LiteralNode literal) {
            executorType = ExecutorType.LITERAL;
            config.put("value", literal.value());
        } else if (node instanceof OperatorNode operator) {
            executorType = ExecutorType.GENERIC_OPERATOR;
            config.put("operatorName", operator.operatorName());
        } else if (node instanceof FeatureOutputNode output) {
            executorType = ExecutorType.FEATURE_OUTPUT;
            config.put("featureName", output.featureName());
            config.put("isRoot", dag.rootNodeIds().contains(output.nodeId()));
        } else {
            throw new IllegalArgumentException("Unsupported logical node: " + node.nodeType());
        }

        ExecutionStage stage = stageFor(node, environment);
        ExecutionMode mode = environment == ExecutionEnvironment.OFFLINE
                ? ExecutionMode.BATCH
                : (stage == ExecutionStage.REQUEST_SHARED ? ExecutionMode.REQUEST : ExecutionMode.BATCH);
        CachePolicy cache = cacheFor(node, environment, stage);
        MaterializationPolicy materialization = materializationFor(node);

        return new PhysicalNode(
                "physical:" + sequence + ":" + node.nodeType().name().toLowerCase(),
                List.of(node.nodeId()),
                executorType,
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
     * 约束 C10：OFFLINE 一律走离线批阶段；ONLINE 下 ITEM 实体域节点走候选批阶段，
     * 其余走请求共享阶段。阶段、模式与缓存策略只允许在此类构建期推导函数中决定。
     */
    private static ExecutionStage stageFor(LogicalNode node, ExecutionEnvironment environment) {
        if (environment == ExecutionEnvironment.OFFLINE) return ExecutionStage.OFFLINE_BATCH;
        return node.entityScopes().contains(EntityScope.ITEM)
                ? ExecutionStage.CANDIDATE_BATCH
                : ExecutionStage.REQUEST_SHARED;
    }

    private static CachePolicy cacheFor(
            LogicalNode node,
            ExecutionEnvironment environment,
            ExecutionStage stage) {
        if (environment == ExecutionEnvironment.ONLINE && stage == ExecutionStage.REQUEST_SHARED) {
            return CachePolicy.REQUEST;
        }
        if (node instanceof OperatorNode operator && "extractIndustry".equals(operator.operatorName())) {
            return environment == ExecutionEnvironment.ONLINE
                    ? CachePolicy.CANDIDATE_KEY : CachePolicy.USER_GROUP;
        }
        return CachePolicy.NONE;
    }

    private static MaterializationPolicy materializationFor(LogicalNode node) {
        if (node.valueShape() == ValueShape.SEQUENCE) return MaterializationPolicy.VIEW;
        return MaterializationPolicy.LAZY;
    }

    private Map<String, CountExtractIndustryMatch> findOnlineFusionMatches(OptimizedLogicalPlan optimized) {
        LogicalDag dag = optimized.dag();
        Map<String, CountExtractIndustryMatch> result = new LinkedHashMap<>();
        for (String nodeId : dag.topologicalOrder()) {
            LogicalNode node = dag.node(nodeId);
            if (!(node instanceof OperatorNode countNode)) continue;
            NodePlanningMetadata metadata = optimized.metadata().node(nodeId);
            if (!"COUNT_EXTRACT_INDUSTRY".equals(metadata.fusionCandidate())) continue;
            CountExtractIndustryMatch match = optimizer.matchCountExtractIndustry(dag, countNode).orElse(null);
            if (match == null) continue;
            // C9：融合前提——extract 节点不是根且引用计数为 1，避免破坏公共子表达式复用
            if (dag.rootNodeIds().contains(match.extractNodeId())
                    || optimized.metadata().node(match.extractNodeId()).referenceCount() != 1) continue;
            boolean unsafeIntermediate = match.intermediateNodeIds().stream().anyMatch(intermediateNodeId ->
                    dag.rootNodeIds().contains(intermediateNodeId)
                            || optimized.metadata().node(intermediateNodeId).referenceCount() != 1);
            if (unsafeIntermediate) continue;
            result.put(nodeId, match);
        }
        return result;
    }

    private static String requireSlot(Map<String, String> slots, String logicalNodeId) {
        String slot = slots.get(logicalNodeId);
        if (slot == null) throw new IllegalStateException("No physical slot for logical node: " + logicalNodeId);
        return slot;
    }
}
