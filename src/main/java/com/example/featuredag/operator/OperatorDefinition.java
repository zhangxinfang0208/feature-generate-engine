package com.example.featuredag.operator;

import com.example.featuredag.logical.LogicalNode;

import java.util.List;

public interface OperatorDefinition {
    String name();
    int minArguments();
    int maxArguments();
    boolean deterministic();
    boolean parameterized();
    boolean supportsSequenceView();
    default boolean sideEffectFree() { return true; }
    default long estimatedCost() { return 1L; }
    default List<OperatorSemantic> semantics() { return List.of(); }
    OperatorInference infer(List<LogicalNode> inputs);
    Object evaluate(List<Object> arguments);
}
