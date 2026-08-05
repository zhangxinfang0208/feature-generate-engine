package com.example.featuredag.logical;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public abstract class AbstractLogicalNode implements LogicalNode {
    private final String nodeId;
    private final NodeType nodeType;
    private final List<NodeInput> inputs;
    private final DataType outputType;
    private final Set<EntityScope> entityScopes;
    private final ValueShape valueShape;
    private final String sourceFeatureName;
    private final String sourceExpression;

    protected AbstractLogicalNode(
            String nodeId,
            NodeType nodeType,
            List<NodeInput> inputs,
            DataType outputType,
            Set<EntityScope> entityScopes,
            ValueShape valueShape,
            String sourceFeatureName,
            String sourceExpression) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.nodeType = Objects.requireNonNull(nodeType, "nodeType");
        this.inputs = List.copyOf(inputs == null ? List.of() : inputs);
        this.outputType = Objects.requireNonNull(outputType, "outputType");
        this.entityScopes = Collections.unmodifiableSet(new LinkedHashSet<>(
                entityScopes == null ? Set.of() : entityScopes));
        this.valueShape = Objects.requireNonNull(valueShape, "valueShape");
        this.sourceFeatureName = sourceFeatureName;
        this.sourceExpression = sourceExpression;
    }

    @Override public String nodeId() { return nodeId; }
    @Override public NodeType nodeType() { return nodeType; }
    @Override public List<NodeInput> inputs() { return inputs; }
    @Override public DataType outputType() { return outputType; }
    @Override public Set<EntityScope> entityScopes() { return entityScopes; }
    @Override public ValueShape valueShape() { return valueShape; }
    @Override public String sourceFeatureName() { return sourceFeatureName; }
    @Override public String sourceExpression() { return sourceExpression; }
}
