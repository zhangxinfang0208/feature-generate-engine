package com.example.featuredag.runtime;

import com.example.featuredag.physical.PhysicalPlan;

/**
 * 显式开启的运行时值观测出口，供本地调测检查每个物理节点的中间结果。
 *
 * <p>与不携带特征值的 {@link RuntimeObserver} 分离，避免生产指标链路意外收集
 * 原始特征或中间值。实现会在执行线程内同步调用，必须仅用于调测或保持足够轻量。</p>
 */
@FunctionalInterface
public interface RuntimeTraceObserver {
    RuntimeTraceObserver NOOP = (executionId, plan, result) -> {};

    void onExecutionFinished(
            String executionId,
            PhysicalPlan plan,
            ExecutionResult result);

    static RuntimeTraceObserver noop() {
        return NOOP;
    }
}
