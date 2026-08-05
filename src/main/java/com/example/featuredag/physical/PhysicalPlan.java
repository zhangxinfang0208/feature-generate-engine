package com.example.featuredag.physical;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PhysicalPlan {
    private final String planId;
    private final ExecutionEnvironment environment;
    private final List<PhysicalNode> nodes;
    private final Map<String, String> outputFeatureSlots;

    public PhysicalPlan(
            String planId,
            ExecutionEnvironment environment,
            List<PhysicalNode> nodes,
            Map<String, String> outputFeatureSlots) {
        this.planId = Objects.requireNonNull(planId, "planId");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.nodes = List.copyOf(nodes);
        this.outputFeatureSlots = Collections.unmodifiableMap(new LinkedHashMap<>(outputFeatureSlots));
    }

    public String planId() { return planId; }
    public ExecutionEnvironment environment() { return environment; }
    public List<PhysicalNode> nodes() { return nodes; }
    public Map<String, String> outputFeatureSlots() { return outputFeatureSlots; }
}
