package com.example.featuredag.planning;

import com.example.featuredag.logical.LogicalDag;

import java.util.Objects;

/**
 * 优化后的逻辑计划（C8）：逻辑 DAG 与其规划元数据的只读绑定，
 * 是规划层交给物理层的输入。
 */
public record OptimizedLogicalPlan(LogicalDag dag, PlannerMetadata metadata) {
    public OptimizedLogicalPlan {
        Objects.requireNonNull(dag, "dag");
        Objects.requireNonNull(metadata, "metadata");
    }
}
