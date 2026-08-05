package com.example.featuredag.planning;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PlannerMetadata {
    private final Map<String, NodePlanningMetadata> byNodeId;

    public PlannerMetadata(Map<String, NodePlanningMetadata> byNodeId) {
        this.byNodeId = Collections.unmodifiableMap(new LinkedHashMap<>(byNodeId));
    }

    public NodePlanningMetadata node(String nodeId) {
        NodePlanningMetadata metadata = byNodeId.get(nodeId);
        if (metadata == null) throw new IllegalArgumentException("No planning metadata for node: " + nodeId);
        return metadata;
    }

    public Map<String, NodePlanningMetadata> byNodeId() {
        return byNodeId;
    }
}
