package com.example.featuredag.runtime;

import com.example.featuredag.physical.PhysicalNode;

/** 注册式专用物理执行器；通用 source/literal/operator/output 仍由 DagRuntime 直接处理。 */
@FunctionalInterface
public interface PhysicalExecutor {
    default void validate(PhysicalNode node) {
    }

    ValueHandle execute(
            PhysicalNode node,
            ExecutionContext context,
            RuntimeNodeState state);
}
