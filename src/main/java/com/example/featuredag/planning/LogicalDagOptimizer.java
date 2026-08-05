package com.example.featuredag.planning;

import com.example.featuredag.logical.FeatureOutputNode;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalNode;
import com.example.featuredag.logical.NodeInput;
import com.example.featuredag.logical.OperatorNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps optimization facts outside logical nodes. The logical node model stays
 * small and semantic; planner-only facts have their own lifecycle.
 */
public final class LogicalDagOptimizer {

    public OptimizedLogicalPlan analyze(LogicalDag dag) {
        Map<String, Integer> referenceCounts = computeReferenceCounts(dag);
        Map<String, Set<String>> reachableRoots = computeReachableRoots(dag);
        Map<String, NodePlanningMetadata> result = new LinkedHashMap<>();

        for (String nodeId : dag.topologicalOrder()) {
            LogicalNode node = dag.node(nodeId);
            String fusionCandidate = null;
            String indexCandidate = null;
            List<String> reuseKeyInputs = List.of();
            long estimatedCost = 1L;
            long estimatedSize = switch (node.valueShape()) {
                case SEQUENCE -> 4_096L;
                case INDEX -> 2_048L;
                default -> 8L;
            };

            if (node instanceof OperatorNode operator) {
                if ("extractIndustry".equals(operator.operatorName())) {
                    indexCandidate = "INDUSTRY_INDEX";
                    reuseKeyInputs = List.of("sequenceHandle", "item_industry");
                    estimatedCost = 1_000L;
                } else if ("count".equals(operator.operatorName()) && matchesCountExtractIndustry(dag, operator)) {
                    fusionCandidate = "COUNT_EXTRACT_INDUSTRY";
                    reuseKeyInputs = List.of("sequenceHandle", "item_industry");
                    estimatedCost = 1_000L;
                }
            }

            result.put(nodeId, new NodePlanningMetadata(
                    referenceCounts.getOrDefault(nodeId, 0),
                    reachableRoots.getOrDefault(nodeId, Set.of()),
                    reuseKeyInputs,
                    fusionCandidate,
                    indexCandidate,
                    estimatedCost,
                    estimatedSize));
        }
        return new OptimizedLogicalPlan(dag, new PlannerMetadata(result));
    }

    public boolean matchesCountExtractIndustry(LogicalDag dag, OperatorNode countNode) {
        if (!"count".equals(countNode.operatorName()) || countNode.inputs().size() != 1) return false;
        LogicalNode input = dag.node(countNode.inputs().get(0).nodeId());
        if (!(input instanceof FeatureOutputNode featureOutput)) return false;
        LogicalNode producer = dag.node(featureOutput.producerNodeId());
        return producer instanceof OperatorNode operator
                && "extractIndustry".equals(operator.operatorName())
                && operator.inputs().size() == 2;
    }

    private static Map<String, Integer> computeReferenceCounts(LogicalDag dag) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String nodeId : dag.nodes().keySet()) counts.put(nodeId, 0);
        for (LogicalNode node : dag.nodes().values()) {
            for (NodeInput input : node.inputs()) {
                counts.compute(input.nodeId(), (k, v) -> v == null ? 1 : v + 1);
            }
        }
        return counts;
    }

    private static Map<String, Set<String>> computeReachableRoots(LogicalDag dag) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String nodeId : dag.nodes().keySet()) result.put(nodeId, new LinkedHashSet<>());
        for (String root : dag.rootNodeIds()) {
            Deque<String> stack = new ArrayDeque<>();
            Set<String> visited = new LinkedHashSet<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                String current = stack.pop();
                if (!visited.add(current)) continue;
                result.get(current).add(root);
                for (NodeInput input : dag.node(current).inputs()) stack.push(input.nodeId());
            }
        }
        return result;
    }
}
