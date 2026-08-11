package com.example.featuredag.operator;

import java.util.List;

/** 单值算子执行契约；其结果是 Batch 执行逐行语义的基准。 */
@FunctionalInterface
public interface SingleOperatorKernel {
    Object evaluate(List<Object> arguments);
}
