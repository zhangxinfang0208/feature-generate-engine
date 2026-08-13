package com.example.featuredag.logical;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.ValueShape;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 算子节点：表达式中的函数调用在逻辑 DAG 中的落点（C5/C6）。
 * 输入经 NodeInput 端口引用；输出类型、实体域与值形状由 OperatorInference 推断，
 * 确定性标志来自算子注册表。
 */
public final class OperatorNode extends AbstractLogicalNode {
    private final String operatorName;
    private final Map<String, Object> operatorParams;
    private final boolean deterministic;

    public OperatorNode(
            String nodeId,
            String operatorName,
            List<NodeInput> inputs,
            DataType outputType,
            Set<EntityScope> entityScopes,
            ValueShape valueShape,
            Map<String, Object> operatorParams,
            boolean deterministic,
            String sourceFeatureName,
            String sourceExpression) {
        super(nodeId, NodeType.OPERATOR, inputs, outputType, entityScopes, valueShape,
                sourceFeatureName, sourceExpression);
        this.operatorName = operatorName;
        this.operatorParams = Map.copyOf(operatorParams == null ? Map.of() : operatorParams);
        this.deterministic = deterministic;
    }

    public String operatorName() { return operatorName; }
    public Map<String, Object> operatorParams() { return operatorParams; }
    public boolean deterministic() { return deterministic; }
}
