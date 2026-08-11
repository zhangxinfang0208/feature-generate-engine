package com.example.featuredag.operator;

import java.util.List;

public interface OperatorDefinition extends SingleOperatorKernel {
    String name();
    int minArguments();
    int maxArguments();
    boolean deterministic();
    boolean parameterized();
    boolean supportsSequenceView();
    default boolean supportsCurriedInvocation() { return false; }
    default boolean sideEffectFree() { return true; }
    default long estimatedCost() { return 1L; }
    default List<OperatorSemantic> semantics() { return List.of(); }
    OperatorInference infer(List<OperatorInputMetadata> inputs);
    @Override
    Object evaluate(List<Object> arguments);
}
