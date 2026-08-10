package com.example.featuredag.physical.rewrite;

import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.planning.OptimizedLogicalPlan;

import java.util.Optional;

/** 可注册的只读 DAG 模式规则；规则只产出改写描述，不修改逻辑节点（C8/C9）。 */
public interface PhysicalRewriteRule {
    String ruleId();
    int priority();

    Optional<PhysicalRewrite> match(
            OptimizedLogicalPlan optimized,
            String rootNodeId,
            ExecutionEnvironment environment,
            OperatorRegistry operatorRegistry);
}
