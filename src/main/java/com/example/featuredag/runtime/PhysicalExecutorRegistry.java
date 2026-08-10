package com.example.featuredag.runtime;

import com.example.featuredag.physical.PhysicalExecutorIds;
import com.example.featuredag.physical.ExecutorType;
import com.example.featuredag.physical.PhysicalPlan;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 专用物理执行器注册表；核心运行时只按 executorId 路由。 */
public final class PhysicalExecutorRegistry {
    private final Map<String, PhysicalExecutor> executors = new LinkedHashMap<>();

    public PhysicalExecutorRegistry register(String executorId, PhysicalExecutor executor) {
        Objects.requireNonNull(executorId, "executorId");
        Objects.requireNonNull(executor, "executor");
        if (executorId.isBlank()) throw new IllegalArgumentException("executorId must not be blank");
        PhysicalExecutor previous = executors.putIfAbsent(executorId, executor);
        if (previous != null) {
            throw new IllegalArgumentException("Physical executor already registered: " + executorId);
        }
        return this;
    }

    public PhysicalExecutor require(String executorId) {
        PhysicalExecutor executor = executors.get(executorId);
        if (executor == null) {
            throw new IllegalArgumentException("No physical executor registered: " + executorId);
        }
        return executor;
    }

    public boolean contains(String executorId) {
        return executors.containsKey(executorId);
    }

    public void validate(PhysicalPlan plan) {
        for (var node : plan.nodes()) {
            if (node.executorType() != ExecutorType.SPECIALIZED) continue;
            require(node.executorId()).validate(node);
        }
    }

    public static PhysicalExecutorRegistry standard(SequenceIndexRegistry indexRegistry) {
        return new PhysicalExecutorRegistry().register(
                PhysicalExecutorIds.SEQUENCE_KEY_COUNT,
                new SequenceKeyCountExecutor(indexRegistry));
    }
}
