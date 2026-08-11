package com.example.featuredag.operator;

import java.util.Objects;

/** Batch 算子的逐行结果；结果行数必须与调用行数一致。 */
public record BatchOperatorResult(BatchColumn values) {
    public BatchOperatorResult {
        Objects.requireNonNull(values, "values");
    }
}
