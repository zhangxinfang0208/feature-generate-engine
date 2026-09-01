package com.example.featuredag.runtime;

import java.util.Objects;

/** 仅在运行时内部传播的算子失败，不得进入公共编码结果或观测详情。 */
final class EvaluationFailure {
    private final RuntimeException cause;
    private final String physicalNodeId;
    private final String location;

    private EvaluationFailure(
            String physicalNodeId,
            String location,
            RuntimeException cause) {
        this.physicalNodeId = Objects.requireNonNull(physicalNodeId, "physicalNodeId");
        this.location = Objects.requireNonNull(location, "location");
        this.cause = Objects.requireNonNull(cause, "cause");
    }

    static EvaluationFailure single(String physicalNodeId, RuntimeException cause) {
        return new EvaluationFailure(physicalNodeId, "scalar value", cause);
    }

    static EvaluationFailure batch(
            String physicalNodeId,
            String location,
            RuntimeException cause) {
        return new EvaluationFailure(physicalNodeId, location, cause);
    }

    RuntimeException cause() {
        return cause;
    }

    String physicalNodeId() {
        return physicalNodeId;
    }

    String location() {
        return location;
    }

    @Override
    public String toString() {
        return "<operator-failure>";
    }
}
