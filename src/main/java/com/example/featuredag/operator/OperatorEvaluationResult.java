package com.example.featuredag.operator;

import java.util.Objects;

/** 单行算子求值结果；失败作为运行时内部数据传播，不在算子层选择业务默认值。 */
public final class OperatorEvaluationResult {
    private final Object value;
    private final RuntimeException failure;

    private OperatorEvaluationResult(Object value, RuntimeException failure) {
        this.value = value;
        this.failure = failure;
    }

    public static OperatorEvaluationResult success(Object value) {
        return new OperatorEvaluationResult(value, null);
    }

    public static OperatorEvaluationResult failure(RuntimeException failure) {
        return new OperatorEvaluationResult(null, Objects.requireNonNull(failure, "failure"));
    }

    public boolean failed() {
        return failure != null;
    }

    public Object value() {
        return value;
    }

    public RuntimeException failure() {
        return failure;
    }
}
