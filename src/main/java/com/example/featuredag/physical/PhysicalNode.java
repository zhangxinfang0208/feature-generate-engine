package com.example.featuredag.physical;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PhysicalNode {
    private final String physicalNodeId;
    private final List<String> logicalNodeIds;
    private final ExecutorType executorType;
    private final ExecutionStage executionStage;
    private final ExecutionMode executionMode;
    private final List<String> inputSlots;
    private final String outputSlot;
    private final CachePolicy cachePolicy;
    private final MaterializationPolicy materializationPolicy;
    private final Map<String, Object> executorConfig;

    public PhysicalNode(
            String physicalNodeId,
            List<String> logicalNodeIds,
            ExecutorType executorType,
            ExecutionStage executionStage,
            ExecutionMode executionMode,
            List<String> inputSlots,
            String outputSlot,
            CachePolicy cachePolicy,
            MaterializationPolicy materializationPolicy,
            Map<String, Object> executorConfig) {
        this.physicalNodeId = Objects.requireNonNull(physicalNodeId, "physicalNodeId");
        this.logicalNodeIds = List.copyOf(logicalNodeIds);
        this.executorType = Objects.requireNonNull(executorType, "executorType");
        this.executionStage = Objects.requireNonNull(executionStage, "executionStage");
        this.executionMode = Objects.requireNonNull(executionMode, "executionMode");
        this.inputSlots = List.copyOf(inputSlots);
        this.outputSlot = Objects.requireNonNull(outputSlot, "outputSlot");
        this.cachePolicy = Objects.requireNonNull(cachePolicy, "cachePolicy");
        this.materializationPolicy = Objects.requireNonNull(materializationPolicy, "materializationPolicy");
        this.executorConfig = Collections.unmodifiableMap(new LinkedHashMap<>(executorConfig));
    }

    public String physicalNodeId() { return physicalNodeId; }
    public List<String> logicalNodeIds() { return logicalNodeIds; }
    public ExecutorType executorType() { return executorType; }
    public ExecutionStage executionStage() { return executionStage; }
    public ExecutionMode executionMode() { return executionMode; }
    public List<String> inputSlots() { return inputSlots; }
    public String outputSlot() { return outputSlot; }
    public CachePolicy cachePolicy() { return cachePolicy; }
    public MaterializationPolicy materializationPolicy() { return materializationPolicy; }
    public Map<String, Object> executorConfig() { return executorConfig; }
}
