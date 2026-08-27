package com.example.featuredag.operator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 按输入行顺序组装 Batch 成功值和失败位置。 */
public final class BatchOperatorResultBuilder {
    private final int expectedRows;
    private final List<Object> values;
    private final Map<Integer, RuntimeException> rowFailures;

    public BatchOperatorResultBuilder(int expectedRows) {
        if (expectedRows < 0) {
            throw new IllegalArgumentException("expectedRows must be non-negative");
        }
        this.expectedRows = expectedRows;
        this.values = new ArrayList<Object>(expectedRows);
        this.rowFailures = new LinkedHashMap<Integer, RuntimeException>();
    }

    public void addValue(Object value) {
        requireRemainingRow();
        values.add(value);
    }

    public void addFailure(RuntimeException failure) {
        requireRemainingRow();
        int rowIndex = values.size();
        values.add(null);
        rowFailures.put(rowIndex, Objects.requireNonNull(failure, "failure"));
    }

    public BatchOperatorResult build() {
        if (values.size() != expectedRows) {
            throw new IllegalStateException(
                    "Batch result has " + values.size() + " rows, expected " + expectedRows);
        }
        return new BatchOperatorResult(ListBatchColumn.owned(values), rowFailures);
    }

    private void requireRemainingRow() {
        if (values.size() >= expectedRows) {
            throw new IllegalStateException("Batch result already contains " + expectedRows + " rows");
        }
    }
}
