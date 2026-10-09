package com.example.featuredag.physical;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

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
    private final List<List<SlotRelease>> releasesAfterNode;

    /** C8/C9：物理槽位最后使用位置；最终根槽始终保留。 */
    public record SlotRelease(String slot, String physicalNodeId) {}

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
        this.releasesAfterNode = buildReleases();
    }

    private List<List<SlotRelease>> buildReleases() {
        Map<String, Integer> lastUses = new LinkedHashMap<>();
        Map<String, String> producers = new LinkedHashMap<>();
        for (int index = 0; index < nodes.size(); index++) {
            PhysicalNode node = nodes.get(index);
            lastUses.putIfAbsent(node.outputSlot(), index);
            producers.put(node.outputSlot(), node.physicalNodeId());
            for (String input : node.inputSlots()) lastUses.put(input, index);
        }
        Set<String> roots = new HashSet<>(outputFeatureSlots.values());
        List<List<SlotRelease>> releases = new ArrayList<>(nodes.size());
        for (int index = 0; index < nodes.size(); index++) releases.add(new ArrayList<>());
        lastUses.forEach((slot, index) -> {
            if (!roots.contains(slot) && producers.containsKey(slot)) {
                releases.get(index).add(new SlotRelease(slot, producers.get(slot)));
            }
        });
        return releases.stream().map(List::copyOf).toList();
    }

    public List<SlotRelease> releasesAfterNode(int nodeIndex) { return releasesAfterNode.get(nodeIndex); }

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
