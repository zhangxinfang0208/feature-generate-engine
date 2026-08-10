package com.example.featuredag.physical;

import com.example.featuredag.logical.ValueShape;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 物理节点：L2 中可执行的最小单元（C9）。
 * 一个物理节点可对应一个或多个逻辑节点（融合），输入/输出通过槽位（slot:N）连接；
 * 执行器类型、阶段、模式、缓存与物化策略在构建期已全部确定（C10）。
 */
public final class PhysicalNode {
    private final String physicalNodeId;
    private final List<String> logicalNodeIds;
    private final ExecutorType executorType;
    private final ExecutionStage executionStage;
    private final ExecutionMode executionMode;
    private final ValueShape logicalValueShape;
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
            ValueShape logicalValueShape,
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
        this.logicalValueShape = Objects.requireNonNull(logicalValueShape, "logicalValueShape");
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
    public ValueShape logicalValueShape() { return logicalValueShape; }
    public List<String> inputSlots() { return inputSlots; }
    public String outputSlot() { return outputSlot; }
    public CachePolicy cachePolicy() { return cachePolicy; }
    public MaterializationPolicy materializationPolicy() { return materializationPolicy; }
    public Map<String, Object> executorConfig() { return executorConfig; }
}
