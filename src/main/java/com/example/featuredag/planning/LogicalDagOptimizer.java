package com.example.featuredag.planning;

import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalNode;
import com.example.featuredag.logical.NodeInput;
import com.example.featuredag.logical.OperatorNode;
import com.example.featuredag.operator.OperatorDefinition;
import com.example.featuredag.operator.OperatorRegistry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Keeps optimization facts outside logical nodes. The logical node model stays
 * small and semantic; planner-only facts have their own lifecycle.
 *
 * 规划层（L1→L2 之间）：只读分析逻辑 DAG（C8），
 * 引用计数、可达根、依赖维度、缓存资格与成本等事实全部外置在 NodePlanningMetadata，
 * 绝不回写逻辑节点，保证逻辑模型保持小且语义化。
 */
public final class LogicalDagOptimizer {
    private static final long DEFAULT_NODE_COST = 1L;
    private static final long ESTIMATED_SEQUENCE_BYTES = 4_096L;
    private static final long ESTIMATED_INDEX_BYTES = 2_048L;
    private static final long ESTIMATED_SCALAR_BYTES = 8L;

    private final OperatorRegistry operatorRegistry;

    public LogicalDagOptimizer() {
        this(OperatorRegistry.standard());
    }

    public LogicalDagOptimizer(OperatorRegistry operatorRegistry) {
        this.operatorRegistry = Objects.requireNonNull(operatorRegistry, "operatorRegistry");
    }

    public OptimizedLogicalPlan analyze(LogicalDag dag) {
        Map<String, Integer> referenceCounts = computeReferenceCounts(dag);
        Map<String, Set<String>> reachableRoots = computeReachableRoots(dag);
        Map<String, NodePlanningMetadata> result = new LinkedHashMap<>();

        for (String nodeId : dag.topologicalOrder()) {
            LogicalNode node = dag.node(nodeId);
            long estimatedCost = DEFAULT_NODE_COST;
            boolean cacheEligible = true;
            long estimatedSize = switch (node.valueShape()) {
                case SEQUENCE -> ESTIMATED_SEQUENCE_BYTES;
                case INDEX -> ESTIMATED_INDEX_BYTES;
                default -> ESTIMATED_SCALAR_BYTES;
            };

            if (node instanceof OperatorNode operator) {
                OperatorDefinition definition = operatorRegistry.find(operator.operatorName()).orElse(null);
                if (definition == null) {
                    cacheEligible = false;
                } else {
                    estimatedCost = definition.estimatedCost();
                    cacheEligible = definition.deterministic() && definition.sideEffectFree();
                }
            }

            result.put(nodeId, new NodePlanningMetadata(
                    referenceCounts.getOrDefault(nodeId, 0),
                    reachableRoots.getOrDefault(nodeId, Set.of()),
                    dependencyDimensions(node),
                    cacheEligible,
                    estimatedCost,
                    estimatedSize));
        }
        // C8：分析产物是按逻辑节点索引的只读元数据表，物理层据此做融合/缓存决策
        return new OptimizedLogicalPlan(dag, new PlannerMetadata(result));
    }

    private static Set<DependencyDimension> dependencyDimensions(LogicalNode node) {
        Set<DependencyDimension> dimensions = new LinkedHashSet<>();
        for (EntityScope scope : node.entityScopes()) {
            dimensions.add(switch (scope) {
                case USER -> DependencyDimension.USER;
                case SCENE -> DependencyDimension.SCENE;
                case ITEM -> DependencyDimension.ITEM;
            });
        }
        if (dimensions.isEmpty()) dimensions.add(DependencyDimension.CONSTANT);
        return dimensions;
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
