package com.example.featuredag.operator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Batch 算子的逐行结果；结果行数必须与调用行数一致。 */
public record BatchOperatorResult(
        BatchColumn values,
        Map<Integer, RuntimeException> rowFailures) {
    public BatchOperatorResult {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(rowFailures, "rowFailures");
        LinkedHashMap<Integer, RuntimeException> copiedFailures = new LinkedHashMap<>();
        for (Map.Entry<Integer, RuntimeException> entry : rowFailures.entrySet()) {
            Integer rowIndex = Objects.requireNonNull(entry.getKey(), "rowFailures key");
            RuntimeException failure = Objects.requireNonNull(
                    entry.getValue(), "rowFailures[" + rowIndex + "]");
            if (rowIndex < 0 || rowIndex >= values.size()) {
                throw new IllegalArgumentException(
                        "Failure row index " + rowIndex + " is outside 0.." + (values.size() - 1));
            }
            copiedFailures.put(rowIndex, failure);
        }
        rowFailures = Collections.unmodifiableMap(copiedFailures);
    }

    public BatchOperatorResult(BatchColumn values) {
        this(values, Collections.emptyMap());
    }

    public boolean hasFailures() {
        return !rowFailures.isEmpty();
    }
}
