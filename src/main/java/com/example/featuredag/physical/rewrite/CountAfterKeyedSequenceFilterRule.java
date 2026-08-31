package com.example.featuredag.physical.rewrite;

import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.logical.FeatureOutputNode;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalNode;
import com.example.featuredag.logical.NodeInput;
import com.example.featuredag.logical.OperatorNode;
import com.example.featuredag.operator.KeyedSequenceFilterSemantic;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.operator.SequenceCardinalitySemantic;
import com.example.featuredag.physical.CachePolicy;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.physical.ExecutionMode;
import com.example.featuredag.physical.ExecutionStage;
import com.example.featuredag.physical.MaterializationPolicy;
import com.example.featuredag.physical.PhysicalExecutorIds;
import com.example.featuredag.planning.OptimizedLogicalPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 把“序列基数聚合(按 key 过滤序列)”改写为通用按 key 索引计数执行器。 */
public final class CountAfterKeyedSequenceFilterRule implements PhysicalRewriteRule {
    public static final String RULE_ID = "count-after-keyed-sequence-filter";

    @Override public String ruleId() { return RULE_ID; }
    @Override public int priority() { return 100; }

    @Override
    public Optional<PhysicalRewrite> match(
            OptimizedLogicalPlan optimized,
            String rootNodeId,
            ExecutionEnvironment environment,
            OperatorRegistry operatorRegistry) {
        // 此优化利用同一请求序列服务多个候选 key，仅在线候选批场景才有预期收益。
        if (environment != ExecutionEnvironment.ONLINE) return Optional.empty();

        LogicalDag dag = optimized.dag();
        LogicalNode root = dag.node(rootNodeId);
        if (!(root instanceof OperatorNode aggregate)) return Optional.empty();

        // C10：通过算子声明的语义识别“序列基数”，不依赖 count_distinct 等业务名称。
        SequenceCardinalitySemantic cardinality = operatorRegistry
                .semantic(aggregate.operatorName(), SequenceCardinalitySemantic.class)
                .orElse(null);
        if (cardinality == null || cardinality.sequenceInputIndex() >= aggregate.inputs().size()) {
            return Optional.empty();
        }

        LogicalNode aggregateInput = dag.node(
                aggregate.inputs().get(cardinality.sequenceInputIndex()).nodeId());
        OperatorNode filter;
        List<String> intermediateNodeIds = new ArrayList<>();
        // 允许聚合直接消费过滤节点，也允许中间隔一个特征输出边界；后者同样必须被安全消费。
        if (aggregateInput instanceof OperatorNode directFilter) {
            filter = directFilter;
        } else if (aggregateInput instanceof FeatureOutputNode featureOutput) {
            // 默认值是特征边界语义；融合若跳过该边界会使空过滤结果无法兜底。
            if (featureOutput.defaultValue() != null) return Optional.empty();
            LogicalNode producer = dag.node(featureOutput.producerNodeId());
            if (!(producer instanceof OperatorNode producerOperator)) return Optional.empty();
            filter = producerOperator;
            intermediateNodeIds.add(featureOutput.nodeId());
        } else {
            return Optional.empty();
        }

        KeyedSequenceFilterSemantic filterSemantic = operatorRegistry
                .semantic(filter.operatorName(), KeyedSequenceFilterSemantic.class)
                .orElse(null);
        if (filterSemantic == null
                || filterSemantic.sequenceInputIndex() >= filter.inputs().size()
                || filterSemantic.keyInputIndex() >= filter.inputs().size()) {
            return Optional.empty();
        }

        if (!optimized.metadata().node(aggregate.nodeId()).cacheEligible()
                || !optimized.metadata().node(filter.nodeId()).cacheEligible()) {
            return Optional.empty();
        }
        // C9：共享节点或根节点不能被融合吞掉，否则其他输出路径将失去独立物理槽。
        if (!safeToConsume(optimized, filter.nodeId())) return Optional.empty();
        for (String intermediateNodeId : intermediateNodeIds) {
            if (!safeToConsume(optimized, intermediateNodeId)) return Optional.empty();
        }

        NodeInput sequenceInput = filter.inputs().get(filterSemantic.sequenceInputIndex());
        NodeInput keyInput = filter.inputs().get(filterSemantic.keyInputIndex());
        // key 必须随 ITEM 变化，才能把该模式安排到候选批阶段；请求级 key 不需要此专用路径。
        if (!dag.node(keyInput.nodeId()).entityScopes().contains(EntityScope.ITEM)) {
            return Optional.empty();
        }
        // 序列必须是请求级共享值：专用执行器按“同一请求序列建一次索引、每个候选 key 查一次计数”
        // 设计，只接受 SequenceValue（见 SequenceKeyCountExecutor）。若序列本身随 ITEM 变化，
        // 运行时会在候选批阶段拿到候选向量而非 SequenceValue，必须回退到未融合执行。
        if (dag.node(sequenceInput.nodeId()).entityScopes().contains(EntityScope.ITEM)) {
            return Optional.empty();
        }
        // C9/C10：融合只暴露序列和候选 key 两个外部输入，并完整记录被消费的逻辑节点。
        List<String> consumedNodeIds = new ArrayList<>();
        consumedNodeIds.add(filter.nodeId());
        consumedNodeIds.addAll(intermediateNodeIds);
        consumedNodeIds.add(aggregate.nodeId());

        // 成本收益门随 estimatedCost 一并移除；该规则一期作为预留扩展点，收益按固定值参与排序
        return Optional.of(new PhysicalRewrite(
                RULE_ID,
                priority(),
                1L,
                aggregate.nodeId(),
                consumedNodeIds,
                List.of(sequenceInput.nodeId(), keyInput.nodeId()),
                PhysicalExecutorIds.SEQUENCE_KEY_COUNT,
                ExecutionStage.CANDIDATE_BATCH,
                ExecutionMode.CANDIDATE_KEY,
                CachePolicy.CANDIDATE_KEY,
                MaterializationPolicy.LAZY,
                true,
                Map.of("keyDomain", filterSemantic.keyDomain().value())));
    }

    private static boolean safeToConsume(OptimizedLogicalPlan optimized, String nodeId) {
        // 非根且只有一个消费者，才能保证融合后不会切断其他输出或共享分支（C9）。
        return !optimized.dag().rootNodeIds().contains(nodeId)
                && optimized.metadata().node(nodeId).referenceCount() == 1;
    }
}
