package com.example.featuredag.logical;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.ValueShape;

import java.util.Set;

/**
 * 字面量节点：表达式中的常量在逻辑 DAG 中的落点（C5），无输入、无实体域。
 */
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
