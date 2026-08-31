package com.example.featuredag.physical;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.logical.FeatureOutputNode;
import com.example.featuredag.logical.LiteralNode;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalNode;
import com.example.featuredag.logical.OperatorNode;
import com.example.featuredag.logical.SourceNode;
import com.example.featuredag.operator.OperatorDefinition;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.physical.rewrite.PhysicalRewrite;
import com.example.featuredag.physical.rewrite.PhysicalRewriteRegistry;
import com.example.featuredag.planning.NodePlanningMetadata;
import com.example.featuredag.planning.OptimizedLogicalPlan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 物理层（L2）转换器：按逻辑拓扑序把逻辑节点映射为物理节点与输出槽（C9）。
 * 专用融合由注册的 PhysicalRewriteRule 描述；本类不按业务算子名称做判断（C10）。
 */
public final class PhysicalPlanner {
    private final OperatorRegistry operatorRegistry;
    private final PhysicalRewriteRegistry rewriteRegistry;

    public PhysicalPlanner() {
        this(OperatorRegistry.standard(), PhysicalRewriteRegistry.standard());
    }

    public PhysicalPlanner(OperatorRegistry operatorRegistry) {
        this(operatorRegistry, PhysicalRewriteRegistry.standard());
    }

    public PhysicalPlanner(
            OperatorRegistry operatorRegistry,
            PhysicalRewriteRegistry rewriteRegistry) {
        this.operatorRegistry = Objects.requireNonNull(operatorRegistry, "operatorRegistry");
        this.rewriteRegistry = Objects.requireNonNull(rewriteRegistry, "rewriteRegistry");
    }

    public PhysicalPlan plan(OptimizedLogicalPlan optimized, ExecutionEnvironment environment, String planId) {
        LogicalDag dag = optimized.dag();
        // 规则选择只读取逻辑 DAG 与规划元数据，先得到不重叠改写集合，再统一进行 L1→L2 转换（C8）。
        Map<String, PhysicalRewrite> rewrites = rewriteRegistry.select(
                optimized, environment, operatorRegistry);
        Set<String> skippedLogicalNodes = new LinkedHashSet<>();
        // C9：融合根仍负责产出最终槽；其余已消费节点从逐节点转换中跳过，避免重复执行和物化。
        for (PhysicalRewrite rewrite : rewrites.values()) {
            for (String consumedNodeId : rewrite.consumedNodeIds()) {
                if (!consumedNodeId.equals(rewrite.rootNodeId())) {
                    skippedLogicalNodes.add(consumedNodeId);
                }
            }
        }

        // C9：按逻辑拓扑序转换；融合节点共享一个输出槽，被安全消费的中间节点不再单独物化。
        Map<String, String> logicalSlots = new HashMap<>();
        List<PhysicalNode> physicalNodes = new ArrayList<>();
        Map<String, String> outputFeatureSlots = new LinkedHashMap<>();
        Map<String, List<String>> affectedFeaturesByLogicalNode =
                affectedTargetFeaturesByLogicalNode(dag);
        Map<String, List<String>> affectedFeaturesByPhysicalNode = new LinkedHashMap<>();
        int sequence = 0;

        for (String logicalNodeId : dag.topologicalOrder()) {
            if (skippedLogicalNodes.contains(logicalNodeId)) continue;
            LogicalNode logicalNode = dag.node(logicalNodeId);
            PhysicalRewrite rewrite = rewrites.get(logicalNodeId);
            String outputSlot = "slot:" + (++sequence);
            PhysicalNode physicalNode;
            if (rewrite != null) {
                // C9：融合节点只连接模式外部的依赖槽，模式内部的边由专用执行器一次性完成。
                List<String> inputSlots = rewrite.externalInputNodeIds().stream()
                        .map(inputNodeId -> requireSlot(logicalSlots, inputNodeId))
                        .toList();
                Map<String, Object> config = new LinkedHashMap<>(rewrite.executorConfig());
                config.put("rewriteRuleId", rewrite.ruleId());
                physicalNode = new PhysicalNode(
                        "physical:" + sequence + ":specialized",
                        rewrite.consumedNodeIds(),
                        ExecutorType.SPECIALIZED,
                        rewrite.executorId(),
                        rewrite.executionStage(),
                        rewrite.executionMode(),
                        logicalNode.valueShape(),
                        inputSlots,
                        outputSlot,
                        rewrite.cachePolicy(),
                        rewrite.materializationPolicy(),
                        config);
            } else {
                List<String> inputSlots = logicalNode.inputs().stream()
                        .map(input -> requireSlot(logicalSlots, input.nodeId()))
                        .toList();
                physicalNode = createGenericPhysicalNode(
                        dag,
                        optimized.metadata().node(logicalNodeId),
                        logicalNode,
                        environment,
                        sequence,
                        inputSlots,
                        outputSlot);
            }
            logicalSlots.put(logicalNodeId, outputSlot);
            physicalNodes.add(physicalNode);
            affectedFeaturesByPhysicalNode.put(
                    physicalNode.physicalNodeId(),
                    affectedTargetFeatures(
                            physicalNode.logicalNodeIds(), affectedFeaturesByLogicalNode));

            if (logicalNode instanceof FeatureOutputNode output
                    && dag.rootNodeIds().contains(output.nodeId())) {
                // 只有目标根建立对外 feature→slot 映射；内部 FEATURE_OUTPUT 仍可作为普通依赖参与执行。
                outputFeatureSlots.put(output.featureName(), physicalNode.outputSlot());
            }
        }

        if (outputFeatureSlots.size() != dag.featureOutputNodeIds().entrySet().stream()
                .filter(entry -> dag.rootNodeIds().contains(entry.getValue()))
                .count()) {
            throw new IllegalStateException("Not every logical feature root has a physical output slot");
        }
        // C9/C10：物理计划固化执行器、阶段、模式、缓存和物化策略，运行时不得临时改写。
        return new PhysicalPlan(
                planId,
                environment,
                physicalNodes,
                outputFeatureSlots,
                affectedFeaturesByPhysicalNode);
    }

    /**
     * C8：从每个目标根反向遍历可达祖先，在规划期固化节点影响的目标特征集合。
     * canonical 公共节点会自然聚合多个目标；运行时只读取结果，不重新遍历逻辑 DAG。
     */
    private static Map<String, List<String>> affectedTargetFeaturesByLogicalNode(LogicalDag dag) {
        Map<String, LinkedHashSet<String>> mutable = new LinkedHashMap<>();
        for (Map.Entry<String, String> output : dag.featureOutputNodeIds().entrySet()) {
            if (!dag.rootNodeIds().contains(output.getValue())) continue;
            Set<String> visited = new LinkedHashSet<>();
            Deque<String> pending = new ArrayDeque<>();
            pending.push(output.getValue());
            while (!pending.isEmpty()) {
                String nodeId = pending.pop();
                if (!visited.add(nodeId)) continue;
                mutable.computeIfAbsent(nodeId, ignored -> new LinkedHashSet<>())
                        .add(output.getKey());
                for (var input : dag.node(nodeId).inputs()) {
                    pending.push(input.nodeId());
                }
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : mutable.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return result;
    }

    /** 融合物理节点合并全部 consumed logical node 的目标集合，保持配置中的目标顺序。 */
    private static List<String> affectedTargetFeatures(
            List<String> logicalNodeIds,
            Map<String, List<String>> affectedFeaturesByLogicalNode) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String logicalNodeId : logicalNodeIds) {
            result.addAll(affectedFeaturesByLogicalNode.getOrDefault(logicalNodeId, List.of()));
        }
        return List.copyOf(result);
    }

    private PhysicalNode createGenericPhysicalNode(
            LogicalDag dag,
            NodePlanningMetadata metadata,
            LogicalNode node,
            ExecutionEnvironment environment,
            int sequence,
            List<String> inputSlots,
            String outputSlot) {
        ExecutorType executorType;
        String executorId;
        Map<String, Object> config = new LinkedHashMap<>();
        if (node instanceof SourceNode source) {
            // SOURCE_BINDING 配置保留外部绑定名、默认值和实体域，运行时据此选择共享/候选输入容器。
            executorType = ExecutorType.SOURCE_BINDING;
            executorId = PhysicalExecutorIds.SOURCE_BINDING;
            config.put("featureName", source.featureName());
            config.put("sourceBinding", source.sourceBinding());
            config.put("defaultValue", source.defaultValue());
            config.put("entityScopes", source.entityScopes().stream().map(Enum::name).toList());
        } else if (node instanceof LiteralNode literal) {
            executorType = ExecutorType.LITERAL;
            executorId = PhysicalExecutorIds.LITERAL;
            config.put("value", literal.value());
        } else if (node instanceof OperatorNode operator) {
            // 通用算子的 Kernel ID 与可用 Batch 实现写入计划，运行时无需重新探测注册能力（C10）。
            executorType = ExecutorType.GENERIC_OPERATOR;
            executorId = PhysicalExecutorIds.GENERIC_OPERATOR;
            OperatorDefinition definition = operatorRegistry.require(operator.operatorName());
            config.put("operatorName", operator.operatorName());
            config.put("singleKernelId", operator.operatorName());
            config.put("batchKernelId", operator.operatorName());
            config.put("batchKernelKind", operatorRegistry.batchKernelKind(operator.operatorName()).name());
            config.put(
                    "invocationPolicy",
                    OperatorInvocationPolicy.SINGLE_OR_BATCH_BY_INPUT_DOMAIN);
            // C10：把算子声明转换为物理输入策略，运行时不再按算子名决策。
            config.put(
                    "sequenceViewInputMode",
                    definition.supportsSequenceView()
                            ? SequenceViewInputMode.DIRECT
                            : SequenceViewInputMode.MATERIALIZE);
        } else if (node instanceof FeatureOutputNode output) {
            executorType = ExecutorType.FEATURE_OUTPUT;
            executorId = PhysicalExecutorIds.FEATURE_OUTPUT;
            config.put("featureName", output.featureName());
            config.put("isRoot", dag.rootNodeIds().contains(output.nodeId()));
            // L1→物理层：衍生默认值固化在 FEATURE_OUTPUT 配置中，运行时无需回查原始模型配置（C1/C10）。
            config.put("defaultValue", output.defaultValue());
            // 定宽决策在物理计划中固化；运行时只执行布尔策略，不解析类型或按算子名特判（C10）。
            config.put("widenIntegralToBigint", output.outputType() == DataType.BIGINT);
            config.put("widenIntegralToDouble", output.outputType() == DataType.DOUBLE);
        } else {
            throw new IllegalArgumentException("Unsupported logical node: " + node.nodeType());
        }

        ExecutionStage stage = stageFor(node, environment);
        ExecutionMode mode = environment == ExecutionEnvironment.OFFLINE
                ? ExecutionMode.BATCH
                : (stage == ExecutionStage.REQUEST_SHARED ? ExecutionMode.REQUEST : ExecutionMode.BATCH);
        // C10：阶段、模式、缓存与物化策略在此固化；运行时只执行这些决策。
        CachePolicy cache = cacheFor(metadata, environment, stage);
        MaterializationPolicy materialization = materializationFor(node);

        return new PhysicalNode(
                "physical:" + sequence + ":" + node.nodeType().name().toLowerCase(),
                List.of(node.nodeId()),
                executorType,
                executorId,
                stage,
                mode,
                node.valueShape(),
                inputSlots,
                outputSlot,
                cache,
                materialization,
                config);
    }

    /**
     * C10：OFFLINE 固定为离线批；ONLINE 的 ITEM 依赖节点走候选批，其余走请求共享阶段。
     */
    private static ExecutionStage stageFor(LogicalNode node, ExecutionEnvironment environment) {
        if (environment == ExecutionEnvironment.OFFLINE) return ExecutionStage.OFFLINE_BATCH;
        return node.entityScopes().contains(EntityScope.ITEM)
                ? ExecutionStage.CANDIDATE_BATCH
                : ExecutionStage.REQUEST_SHARED;
    }

    /**
     * C10：ONLINE 请求共享节点标记 REQUEST 预留缓存策略（运行时一期不消费，仅记录计划意图）；
     * 缓存资格已由规划元数据按 deterministic && sideEffectFree 把关（C8），其余节点不缓存。
     */
    private static CachePolicy cacheFor(
            NodePlanningMetadata metadata,
            ExecutionEnvironment environment,
            ExecutionStage stage) {
        // 非确定性或有副作用的节点在规划期直接排除，运行时不会再尝试缓存。
        if (!metadata.cacheEligible()) return CachePolicy.NONE;
        if (environment == ExecutionEnvironment.ONLINE && stage == ExecutionStage.REQUEST_SHARED) {
            return CachePolicy.REQUEST;
        }
        return CachePolicy.NONE;
    }

    private static MaterializationPolicy materializationFor(LogicalNode node) {
        // 序列优先保留视图以共享底层块和选择范围；标量等普通值延迟物化到输出边界。
        if (node.valueShape() == ValueShape.SEQUENCE) return MaterializationPolicy.VIEW;
        return MaterializationPolicy.LAZY;
    }

    private static String requireSlot(Map<String, String> slots, String logicalNodeId) {
        String slot = slots.get(logicalNodeId);
        if (slot == null) throw new IllegalStateException("No physical slot for logical node: " + logicalNodeId);
        return slot;
    }
}
