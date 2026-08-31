package com.example.featuredag.runtime;

import com.example.featuredag.physical.PhysicalNode;

import java.util.List;
import java.util.Objects;

/**
 * 物理节点执行失败的结构化上下文。
 * 目标特征集合由规划期反向可达分析生成，运行时只附加节点身份并保留原始异常（C8-C10）。
 */
public final class RuntimeNodeExecutionException extends RuntimeException {
    private final String physicalNodeId;
    private final List<String> logicalNodeIds;
    private final String executorId;
    private final List<String> affectedFeatureNames;

    public RuntimeNodeExecutionException(
            PhysicalNode node,
            List<String> affectedFeatureNames,
            RuntimeException cause) {
        super(format(node, affectedFeatureNames, cause), cause);
        this.physicalNodeId = node.physicalNodeId();
        this.logicalNodeIds = List.copyOf(node.logicalNodeIds());
        this.executorId = node.executorId();
        this.affectedFeatureNames = List.copyOf(Objects.requireNonNull(
                affectedFeatureNames, "affectedFeatureNames"));
    }

    public String physicalNodeId() { return physicalNodeId; }
    public List<String> logicalNodeIds() { return logicalNodeIds; }
    public String executorId() { return executorId; }
    public List<String> affectedFeatureNames() { return affectedFeatureNames; }

    private static String format(
            PhysicalNode node,
            List<String> affectedFeatureNames,
            RuntimeException cause) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(affectedFeatureNames, "affectedFeatureNames");
        Objects.requireNonNull(cause, "cause");
        return "Physical node " + node.physicalNodeId()
                + " failed [executor=" + node.executorId()
                + ", logicalNodes=" + node.logicalNodeIds()
                + ", affectedFeatures=" + affectedFeatureNames
                + "]: " + cause.getMessage();
    }
}
