package com.example.featuredag.physical;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 物理层（L2）产物：不可变的物理节点序列与输出特征槽位映射（C9/C10）。
 * 由 PhysicalPlanner 一次性构建，构建完成后仅被运行时消费，不再可变。
 */
public final class PhysicalPlan {
    private final String planId;
    private final ExecutionEnvironment environment;
    private final List<PhysicalNode> nodes;
    private final Map<String, String> outputFeatureSlots;
    private final Map<String, List<String>> affectedFeatureNamesByPhysicalNode;

    public PhysicalPlan(
            String planId,
            ExecutionEnvironment environment,
            List<PhysicalNode> nodes,
            Map<String, String> outputFeatureSlots) {
        this(planId, environment, nodes, outputFeatureSlots, Map.of());
    }

    public PhysicalPlan(
            String planId,
            ExecutionEnvironment environment,
            List<PhysicalNode> nodes,
            Map<String, String> outputFeatureSlots,
            Map<String, List<String>> affectedFeatureNamesByPhysicalNode) {
        this.planId = Objects.requireNonNull(planId, "planId");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.nodes = List.copyOf(nodes);
        this.outputFeatureSlots = Collections.unmodifiableMap(new LinkedHashMap<>(outputFeatureSlots));
        Map<String, List<String>> affectedFeatures = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry
                : Objects.requireNonNull(
                        affectedFeatureNamesByPhysicalNode,
                        "affectedFeatureNamesByPhysicalNode").entrySet()) {
            affectedFeatures.put(
                    Objects.requireNonNull(entry.getKey(), "physicalNodeId"),
                    List.copyOf(Objects.requireNonNull(entry.getValue(), "affectedFeatureNames")));
        }
        this.affectedFeatureNamesByPhysicalNode = Collections.unmodifiableMap(affectedFeatures);
    }

    public String planId() { return planId; }
    public ExecutionEnvironment environment() { return environment; }
    public List<PhysicalNode> nodes() { return nodes; }
    public Map<String, String> outputFeatureSlots() { return outputFeatureSlots; }
    public Map<String, List<String>> affectedFeatureNamesByPhysicalNode() {
        return affectedFeatureNamesByPhysicalNode;
    }

    public List<String> affectedFeatureNames(String physicalNodeId) {
        return affectedFeatureNamesByPhysicalNode.getOrDefault(
                Objects.requireNonNull(physicalNodeId, "physicalNodeId"), List.of());
    }
}
