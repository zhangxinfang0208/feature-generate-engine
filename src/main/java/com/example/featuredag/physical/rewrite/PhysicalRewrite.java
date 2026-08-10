package com.example.featuredag.physical.rewrite;

import com.example.featuredag.physical.CachePolicy;
import com.example.featuredag.physical.ExecutionMode;
import com.example.featuredag.physical.ExecutionStage;
import com.example.featuredag.physical.MaterializationPolicy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 一次已匹配的物理改写：描述被融合节点、外部输入和最终执行策略（C9/C10）。 */
public record PhysicalRewrite(
        String ruleId,
        int priority,
        long estimatedBenefit,
        String rootNodeId,
        List<String> consumedNodeIds,
        List<String> externalInputNodeIds,
        String executorId,
        ExecutionStage executionStage,
        ExecutionMode executionMode,
        CachePolicy cachePolicy,
        MaterializationPolicy materializationPolicy,
        Map<String, Object> executorConfig) {

    public PhysicalRewrite {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(rootNodeId, "rootNodeId");
        Objects.requireNonNull(executorId, "executorId");
        Objects.requireNonNull(executionStage, "executionStage");
        Objects.requireNonNull(executionMode, "executionMode");
        Objects.requireNonNull(cachePolicy, "cachePolicy");
        Objects.requireNonNull(materializationPolicy, "materializationPolicy");
        consumedNodeIds = List.copyOf(consumedNodeIds);
        externalInputNodeIds = List.copyOf(externalInputNodeIds);
        executorConfig = Collections.unmodifiableMap(new LinkedHashMap<>(executorConfig));
        if (!consumedNodeIds.contains(rootNodeId)) {
            throw new IllegalArgumentException("consumedNodeIds must contain rootNodeId");
        }
    }
}
