package com.example.featuredag.runtime;

import com.example.featuredag.definition.ValueShape;

import java.util.Objects;

/** Single 求值失败句柄；失败只能在特征输出边界被消费。 */
public final class FailedValueHandle implements ValueHandle {
    private final ValueShape shape;
    private final EvaluationFailure failure;

    FailedValueHandle(ValueShape shape, EvaluationFailure failure) {
        this.shape = Objects.requireNonNull(shape, "shape");
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    EvaluationFailure failure() {
        return failure;
    }

    @Override
    public ValueShape shape() {
        return shape;
    }

    @Override
    public Object raw() {
        return failure;
    }
}
