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
import java.util.Optional;
import java.util.Set;

/**
 * Keeps optimization facts outside logical nodes. The logical node model stays
 * small and semantic; planner-only facts have their own lifecycle.
 *
 * 规划层（L1→L2 之间）：只读分析逻辑 DAG（C8），
 * 引用计数、可达根与融合/索引候选等优化事实全部外置在 NodePlanningMetadata，
 * 绝不回写逻辑节点，保证逻辑模型保持小且语义化。
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
                // C8：候选识别——extractIndustry 节点可走行业索引；count 节点匹配 extractIndustry 时可融合
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
        // C8：分析产物是按逻辑节点索引的只读元数据表，物理层据此做融合/缓存决策
        return new OptimizedLogicalPlan(dag, new PlannerMetadata(result));
    }

    public boolean matchesCountExtractIndustry(LogicalDag dag, OperatorNode countNode) {
        return matchCountExtractIndustry(dag, countNode).isPresent();
    }

    public Optional<CountExtractIndustryMatch> matchCountExtractIndustry(
            LogicalDag dag, OperatorNode countNode) {
        if (!"count".equals(countNode.operatorName()) || countNode.inputs().size() != 1) {
            return Optional.empty();
        }
        LogicalNode input = dag.node(countNode.inputs().get(0).nodeId());
        OperatorNode extract;
        List<String> intermediateNodeIds;
        if (input instanceof OperatorNode directOperator) {
            extract = directOperator;
            intermediateNodeIds = List.of();
        } else if (input instanceof FeatureOutputNode featureOutput) {
            LogicalNode producer = dag.node(featureOutput.producerNodeId());
            if (!(producer instanceof OperatorNode producerOperator)) return Optional.empty();
            extract = producerOperator;
            intermediateNodeIds = List.of(featureOutput.nodeId());
        } else {
            return Optional.empty();
        }
        if (!"extractIndustry".equals(extract.operatorName()) || extract.inputs().size() != 2) {
            return Optional.empty();
        }
        return Optional.of(new CountExtractIndustryMatch(
                countNode.nodeId(), extract.nodeId(), intermediateNodeIds));
    }

    /**
     * 引用计数（C8）：统计每个逻辑节点被多少条输入边引用，
     * 是公共子表达式复用与融合安全性判断（C9 要求引用计数为 1）的依据。
     */
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

    /**
     * 可达根集合（C8）：自根节点反向遍历，记录每个节点能被哪些根特征到达；
     * 供物理层判断节点在各输出路径上的价值与缓存范围。
     */
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
