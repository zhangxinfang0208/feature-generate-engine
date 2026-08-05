package com.example.featuredag.logical;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;

import java.util.List;
import java.util.Set;

public interface LogicalNode {
    String nodeId();
    NodeType nodeType();
    List<NodeInput> inputs();
    DataType outputType();
    Set<EntityScope> entityScopes();
    ValueShape valueShape();
    String sourceFeatureName();
    String sourceExpression();
}
