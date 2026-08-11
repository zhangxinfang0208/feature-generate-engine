package com.example.featuredag.logical;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInputMetadata;

import java.util.List;
import java.util.Set;

/**
 * 逻辑层（L1）节点契约（C7）：节点是不可变的数据载体，
 * 只描述"是什么"（类型、实体域、值形状）与"依赖谁"（inputs），
 * 不携带规划或执行细节；规划事实外置在 planning 包（C8）。
 */
public interface LogicalNode extends OperatorInputMetadata {
    String nodeId();
    NodeType nodeType();
    List<NodeInput> inputs();
    DataType outputType();
    Set<EntityScope> entityScopes();
    ValueShape valueShape();
    String sourceFeatureName();
    String sourceExpression();
}
