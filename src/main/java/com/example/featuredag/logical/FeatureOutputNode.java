package com.example.featuredag.logical;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;

import java.util.List;
import java.util.Set;

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
