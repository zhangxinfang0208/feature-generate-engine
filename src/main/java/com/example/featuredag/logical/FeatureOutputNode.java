package com.example.featuredag.logical;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.ValueShape;

import java.util.List;
import java.util.Set;

/**
 * 特征输出节点：每个特征在逻辑 DAG 中的唯一边界节点（C3），
 * 单输入（producer），输出角色决定它对外是变换输出/模型输入还是内部节点。
 */
public final class FeatureOutputNode extends AbstractLogicalNode {
    private final String featureName;
    private final String producerNodeId;
    private final OutputRole outputRole;

    public FeatureOutputNode(
            String nodeId,
            String featureName,
            String producerNodeId,
            DataType outputType,
            Set<EntityScope> entityScopes,
            ValueShape valueShape,
            OutputRole outputRole,
            String sourceExpression) {
        super(nodeId, NodeType.FEATURE_OUTPUT,
                List.of(NodeInput.positional(producerNodeId, 0)),
                outputType, entityScopes, valueShape, featureName, sourceExpression);
        this.featureName = featureName;
        this.producerNodeId = producerNodeId;
        this.outputRole = outputRole;
    }

    public String featureName() { return featureName; }
    public String producerNodeId() { return producerNodeId; }
    public OutputRole outputRole() { return outputRole; }
}
