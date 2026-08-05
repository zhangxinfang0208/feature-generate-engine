package com.example.featuredag.logical;

import com.example.featuredag.definition.DataType;

import java.util.Set;

public final class LiteralNode extends AbstractLogicalNode {
    private final Object value;

    public LiteralNode(String nodeId, Object value, DataType outputType, ValueShape valueShape,
                       String sourceFeatureName, String sourceExpression) {
        super(nodeId, NodeType.LITERAL, java.util.List.of(), outputType, Set.of(), valueShape,
                sourceFeatureName, sourceExpression);
        this.value = value;
    }

    public Object value() { return value; }
}
