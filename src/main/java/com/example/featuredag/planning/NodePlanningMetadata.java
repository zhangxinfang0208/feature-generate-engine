package com.example.featuredag.planning;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 规划事实载体（C8）：每个逻辑节点一份只读的优化元数据，
 * 由 LogicalDagOptimizer 计算、PhysicalPlanner 消费，生命周期独立于逻辑 DAG。
 */
public record NodePlanningMetadata(
        int referenceCount,
        Set<String> reachableRootNodeIds,
        boolean cacheEligible,
        long estimatedSizeBytes) {

    public NodePlanningMetadata {
        reachableRootNodeIds = Collections.unmodifiableSet(new LinkedHashSet<>(reachableRootNodeIds));
    }
}
