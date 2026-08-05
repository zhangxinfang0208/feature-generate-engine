package com.example.featuredag.logical;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;

import java.util.Set;

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
