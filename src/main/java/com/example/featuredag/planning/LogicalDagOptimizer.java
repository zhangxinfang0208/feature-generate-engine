package com.example.featuredag.planning;

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
 * 引用计数、可达根、缓存资格与规模估算等事实全部外置在 NodePlanningMetadata，
 * 绝不回写逻辑节点，保证逻辑模型保持小且语义化。
 */
public final class LogicalDagOptimizer {
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
        // 全图事实先独立计算，再按拓扑序汇总到每个节点，便于新增规划指标而不污染逻辑模型。
        Map<String, Integer> referenceCounts = computeReferenceCounts(dag);
        Map<String, Set<String>> reachableRoots = computeReachableRoots(dag);
        Map<String, NodePlanningMetadata> result = new LinkedHashMap<>();

        for (String nodeId : dag.topologicalOrder()) {
            LogicalNode node = dag.node(nodeId);
            // C8：这里只计算物理规划需要的“事实”，不把规模或缓存资格写回逻辑节点。
            boolean cacheEligible = true;
            long estimatedSize = switch (node.valueShape()) {
                case SEQUENCE -> ESTIMATED_SEQUENCE_BYTES;
                case INDEX -> ESTIMATED_INDEX_BYTES;
                default -> ESTIMATED_SCALAR_BYTES;
            };

            if (node instanceof OperatorNode operator) {
                OperatorDefinition definition = operatorRegistry.find(operator.operatorName()).orElse(null);
                if (definition == null) {
                    // 注册表缺失时保守禁用缓存；真正的未知算子通常已在逻辑构建阶段被拒绝。
                    cacheEligible = false;
                } else {
                    // 只有确定且无副作用的求值才允许复用，避免缓存改变可观察语义。
                    cacheEligible = definition.deterministic() && definition.sideEffectFree();
                }
            }

            result.put(nodeId, new NodePlanningMetadata(
                    referenceCounts.getOrDefault(nodeId, 0),
                    reachableRoots.getOrDefault(nodeId, Set.of()),
                    cacheEligible,
                    estimatedSize));
        }
        // C8：分析产物是按逻辑节点索引的只读元数据表，物理层据此做融合/缓存决策
        return new OptimizedLogicalPlan(dag, new PlannerMetadata(result));
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
