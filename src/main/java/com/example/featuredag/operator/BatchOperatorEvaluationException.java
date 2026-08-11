package com.example.featuredag.operator;

import java.util.Objects;

/** Batch 求值失败，并保留 Batch 内行号供运行时映射为 group/candidate 位置。 */
public final class BatchOperatorEvaluationException extends RuntimeException {
    private final int rowIndex;

    public BatchOperatorEvaluationException(int rowIndex, RuntimeException cause) {
        super(Objects.requireNonNull(cause, "cause").getMessage(), cause);
        if (rowIndex < 0) throw new IllegalArgumentException("rowIndex must be non-negative");
        this.rowIndex = rowIndex;
    }

    public int rowIndex() {
        return rowIndex;
    }
}
