package com.example.featuredag.logical;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OperatorNode extends AbstractLogicalNode {
    private final String operatorName;
    private final Map<String, Object> operatorParams;
    private final boolean deterministic;
    private final boolean parameterized;

    public OperatorNode(
            String nodeId,
            String operatorName,
            List<NodeInput> inputs,
            DataType outputType,
            Set<EntityScope> entityScopes,
            ValueShape valueShape,
            Map<String, Object> operatorParams,
            boolean deterministic,
            boolean parameterized,
            String sourceFeatureName,
            String sourceExpression) {
        super(nodeId, NodeType.OPERATOR, inputs, outputType, entityScopes, valueShape,
                sourceFeatureName, sourceExpression);
        this.operatorName = operatorName;
        this.operatorParams = Map.copyOf(operatorParams == null ? Map.of() : operatorParams);
        this.deterministic = deterministic;
        this.parameterized = parameterized;
    }

    public String operatorName() { return operatorName; }
    public Map<String, Object> operatorParams() { return operatorParams; }
    public boolean deterministic() { return deterministic; }
    public boolean parameterized() { return parameterized; }
}
