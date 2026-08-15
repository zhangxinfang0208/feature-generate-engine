package com.example.featuredag.operator;

import java.util.Collections;
import java.util.List;

public interface OperatorDefinition extends SingleOperatorKernel {
    String name();
    int minArguments();
    int maxArguments();
    boolean deterministic();
    /**
     * Whether Single and registered Native Batch kernels can consume OperatorSequence directly.
     * False means the generic runtime materializes an immutable List at the kernel boundary.
     */
    boolean supportsSequenceView();
    default boolean supportsCurriedInvocation() { return false; }
    /**
     * Optional names for arguments, listed in positional order. An empty list means that the
     * operator accepts positional arguments only. Named arguments are normalized by the logical
     * builder and never enter the persisted plan or runtime kernel protocol.
     */
    default List<String> parameterNames() { return Collections.emptyList(); }
    /** 默认 false：有副作用的算子必须显式声明，避免被误判为可缓存（AGENTS.md 缓存资格约束）。 */
    default boolean sideEffectFree() { return false; }
    default List<OperatorSemantic> semantics() { return List.of(); }
    OperatorInference infer(List<OperatorInputMetadata> inputs);
    @Override
    Object evaluate(List<Object> arguments);
}
