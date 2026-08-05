package com.example.featuredag.logical;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class LogicalDag {
    private final Map<String, LogicalNode> nodes;
    private final Set<String> rootNodeIds;
    private final Map<String, String> featureOutputNodeIds;
    private final List<String> topologicalOrder;

    public LogicalDag(
            Map<String, LogicalNode> nodes,
            Set<String> rootNodeIds,
            Map<String, String> featureOutputNodeIds,
            List<String> topologicalOrder) {
        this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        this.rootNodeIds = Collections.unmodifiableSet(new LinkedHashSet<>(rootNodeIds));
        this.featureOutputNodeIds = Collections.unmodifiableMap(new LinkedHashMap<>(featureOutputNodeIds));
        this.topologicalOrder = List.copyOf(topologicalOrder);
        validateReferences();
    }

    public Map<String, LogicalNode> nodes() { return nodes; }
    public Set<String> rootNodeIds() { return rootNodeIds; }
    public Map<String, String> featureOutputNodeIds() { return featureOutputNodeIds; }
    public List<String> topologicalOrder() { return topologicalOrder; }

    public LogicalNode node(String nodeId) {
        LogicalNode node = nodes.get(nodeId);
        if (node == null) throw new IllegalArgumentException("Unknown logical node: " + nodeId);
        return node;
    }

    public FeatureOutputNode featureOutput(String featureName) {
        String nodeId = featureOutputNodeIds.get(featureName);
        if (nodeId == null) throw new IllegalArgumentException("Feature not in DAG: " + featureName);
        return (FeatureOutputNode) node(nodeId);
    }

    public List<LogicalNode> orderedNodes() {
        List<LogicalNode> ordered = new ArrayList<>(topologicalOrder.size());
        for (String id : topologicalOrder) ordered.add(node(id));
        return List.copyOf(ordered);
    }

    private void validateReferences() {
        for (LogicalNode node : nodes.values()) {
            for (NodeInput input : node.inputs()) {
                if (!nodes.containsKey(input.nodeId())) {
                    throw new IllegalArgumentException(
                            "Node " + node.nodeId() + " references unknown input " + input.nodeId());
                }
            }
        }
        for (String root : rootNodeIds) {
            Objects.requireNonNull(nodes.get(root), "Unknown root node: " + root);
        }
    }
}
