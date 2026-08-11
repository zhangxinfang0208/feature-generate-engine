package com.example.featuredag.logical;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.ValueShape;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 逻辑节点公共基类（C7）：全部字段在构造时拷贝为不可变集合，
 * 子类只允许追加只读字段，禁止提供任何可变修改入口。
 */
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
