package com.example.featuredag.logical;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;

import java.util.Set;

/**
 * 源节点：L0 定义的 RAW 特征在逻辑 DAG 中的落点（C2/C5）。
 * 无输入，携带特征名、默认值与源绑定名，实体域来自特征定义声明。
 */
public final class SourceNode extends AbstractLogicalNode {
    private final String featureName;
    private final Object defaultValue;
    private final String sourceBinding;

    public SourceNode(
            String nodeId,
            String featureName,
            DataType outputType,
            Set<EntityScope> entityScopes,
            ValueShape valueShape,
            Object defaultValue,
            String sourceBinding) {
        super(nodeId, NodeType.SOURCE, java.util.List.of(), outputType, entityScopes,
                valueShape, featureName, null);
        this.featureName = featureName;
        this.defaultValue = defaultValue;
        this.sourceBinding = sourceBinding;
    }

    public String featureName() { return featureName; }
    public Object defaultValue() { return defaultValue; }
    public String sourceBinding() { return sourceBinding; }
}
