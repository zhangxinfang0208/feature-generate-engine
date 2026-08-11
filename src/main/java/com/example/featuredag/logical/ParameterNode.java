package com.example.featuredag.logical;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.ValueShape;

import java.util.Set;

public final class ParameterNode extends AbstractLogicalNode {
    private final String parameterName;
    private final Object parameterValue;

    public ParameterNode(String nodeId, String parameterName, Object parameterValue,
                         String sourceFeatureName, String sourceExpression) {
        super(nodeId, NodeType.PARAMETER, java.util.List.of(), DataType.OBJECT, Set.of(),
                ValueShape.OBJECT, sourceFeatureName, sourceExpression);
        this.parameterName = parameterName;
        this.parameterValue = parameterValue;
    }

    public String parameterName() { return parameterName; }
    public Object parameterValue() { return parameterValue; }
}
