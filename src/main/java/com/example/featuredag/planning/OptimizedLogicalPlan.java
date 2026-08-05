package com.example.featuredag.planning;

import com.example.featuredag.logical.LogicalDag;

import java.util.Objects;

public record OptimizedLogicalPlan(LogicalDag dag, PlannerMetadata metadata) {
    public OptimizedLogicalPlan {
        Objects.requireNonNull(dag, "dag");
        Objects.requireNonNull(metadata, "metadata");
    }
}
