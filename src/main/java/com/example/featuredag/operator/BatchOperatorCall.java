package com.example.featuredag.operator;

import java.util.List;
import java.util.Objects;

/** 已完成广播对齐的 Batch 算子调用。每个参数列都必须与布局具有相同行数。 */
public record BatchOperatorCall(
        BatchLayout layout,
        List<BatchColumn> arguments) {

    public BatchOperatorCall {
        Objects.requireNonNull(layout, "layout");
        arguments = List.copyOf(arguments);
        for (int index = 0; index < arguments.size(); index++) {
            BatchColumn argument = Objects.requireNonNull(
                    arguments.get(index), "arguments[" + index + "]");
            if (argument.size() != layout.rowCount()) {
                throw new IllegalArgumentException(
                        "Batch argument " + index + " has size " + argument.size()
                                + ", expected " + layout.rowCount());
            }
        }
    }

    public int rowCount() {
        return layout.rowCount();
    }
}
