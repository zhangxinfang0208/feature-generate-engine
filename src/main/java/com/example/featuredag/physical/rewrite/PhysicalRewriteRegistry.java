package com.example.featuredag.physical.rewrite;

import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.planning.OptimizedLogicalPlan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 物理改写规则注册表；按优先级、收益和拓扑序确定性选择互不重叠的改写。 */
public final class PhysicalRewriteRegistry {
    private final Map<String, PhysicalRewriteRule> rules = new LinkedHashMap<>();

    public PhysicalRewriteRegistry register(PhysicalRewriteRule rule) {
        Objects.requireNonNull(rule, "rule");
        PhysicalRewriteRule previous = rules.putIfAbsent(rule.ruleId(), rule);
        if (previous != null) {
            throw new IllegalArgumentException("Physical rewrite rule already registered: " + rule.ruleId());
        }
        return this;
    }

    public Map<String, PhysicalRewrite> select(
            OptimizedLogicalPlan optimized,
            ExecutionEnvironment environment,
            OperatorRegistry operatorRegistry) {
        Map<String, Integer> topologicalIndex = new LinkedHashMap<>();
        for (int index = 0; index < optimized.dag().topologicalOrder().size(); index++) {
            topologicalIndex.put(optimized.dag().topologicalOrder().get(index), index);
        }

        List<PhysicalRewrite> candidates = new ArrayList<>();
        for (String nodeId : optimized.dag().topologicalOrder()) {
            for (PhysicalRewriteRule rule : rules.values()) {
                rule.match(optimized, nodeId, environment, operatorRegistry).ifPresent(candidates::add);
            }
        }
        candidates.sort(Comparator
                .comparingInt(PhysicalRewrite::priority).reversed()
                .thenComparing(Comparator.comparingLong(PhysicalRewrite::estimatedBenefit).reversed())
                .thenComparingInt(rewrite -> topologicalIndex.get(rewrite.rootNodeId()))
                .thenComparing(PhysicalRewrite::ruleId));

        Set<String> consumed = new LinkedHashSet<>();
        List<PhysicalRewrite> accepted = new ArrayList<>();
        for (PhysicalRewrite candidate : candidates) {
            if (candidate.consumedNodeIds().stream().anyMatch(consumed::contains)) continue;
            accepted.add(candidate);
            consumed.addAll(candidate.consumedNodeIds());
        }
        accepted.sort(Comparator.comparingInt(rewrite -> topologicalIndex.get(rewrite.rootNodeId())));

        Map<String, PhysicalRewrite> result = new LinkedHashMap<>();
        for (PhysicalRewrite rewrite : accepted) result.put(rewrite.rootNodeId(), rewrite);
        return Map.copyOf(result);
    }

    public static PhysicalRewriteRegistry standard() {
        return new PhysicalRewriteRegistry().register(new CountAfterKeyedSequenceFilterRule());
    }
}
