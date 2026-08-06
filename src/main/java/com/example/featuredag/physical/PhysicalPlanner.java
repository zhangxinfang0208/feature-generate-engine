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

public final class PhysicalPlanner {
    private final LogicalDagOptimizer optimizer = new LogicalDagOptimizer();

    public PhysicalPlan plan(OptimizedLogicalPlan optimized, ExecutionEnvironment environment, String planId) {
        LogicalDag dag = optimized.dag();
        Map<String, CountExtractIndustryMatch> fusionByCountNode = environment == ExecutionEnvironment.ONLINE
                ? findOnlineFusionMatches(optimized) : Map.of();
        Set<String> skippedLogicalNodes = new LinkedHashSet<>();
        for (CountExtractIndustryMatch match : fusionByCountNode.values()) {
            skippedLogicalNodes.add(match.extractNodeId());
            skippedLogicalNodes.addAll(match.intermediateNodeIds());
        }

        Map<String, String> logicalSlots = new HashMap<>();
        List<PhysicalNode> physicalNodes = new ArrayList<>();
        Map<String, String> outputFeatureSlots = new LinkedHashMap<>();
        int sequence = 0;

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

        return new PhysicalPlan(planId, environment, physicalNodes, outputFeatureSlots);
    }

    private PhysicalNode createGenericPhysicalNode(
            LogicalDag dag,
            LogicalNode node,
            ExecutionEnvironment environment,
            int sequence,
            List<String> inputSlots,
            String outputSlot) {
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
                inputSlots,
                outputSlot,
                cache,
                materialization,
                config);
    }

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
