package com.example.featuredag.physical.rewrite;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.FeatureDefinition;
import com.example.featuredag.definition.FeatureRole;
import com.example.featuredag.definition.OutputPolicy;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.operator.KeyedSequenceFilterSemantic;
import com.example.featuredag.operator.OperatorDefinition;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.operator.OperatorSemantic;
import com.example.featuredag.operator.SequenceCardinalitySemantic;
import com.example.featuredag.operator.SequenceKeyDomains;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.planning.LogicalDagOptimizer;
import com.example.featuredag.planning.OptimizedLogicalPlan;

import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 覆盖 CountAfterKeyedSequenceFilterRule 对 sequenceInput 实体域的校验：
 * 序列输入必须是请求级共享值，随 ITEM 变化的序列必须回退到未融合执行（见 SequenceKeyCountExecutor
 * 对 SequenceValue 的硬性要求）。当前标准算子清单没有任何算子声明触发该规则所需的语义
 * （KeyedSequenceFilterSemantic/SequenceCardinalitySemantic），因此用最小合成算子直接驱动规则匹配。
 */
public class CountAfterKeyedSequenceFilterRuleTest {

    @Test
    public void refusesToFuseWhenSequenceInputIsItemScoped() {
        // 序列和 key 都随 ITEM 变化：运行时序列输入会是候选向量而非 SequenceValue。
        Optional<PhysicalRewrite> match = matchRewrite(EntityScope.ITEM);
        assertFalse("ITEM 域序列必须回退到未融合执行", match.isPresent());
    }

    @Test
    public void fusesWhenSequenceInputIsRequestScoped() {
        // 序列请求级共享、key 随 ITEM 变化：专用执行器的既有设计场景，确认修复未破坏正常路径。
        Optional<PhysicalRewrite> match = matchRewrite(EntityScope.USER);
        assertTrue("请求级共享序列应当仍可融合", match.isPresent());
    }

    @Test
    public void refusesToFuseAcrossDerivedFeatureDefaultBoundary() {
        OperatorRegistry registry = new OperatorRegistry()
                .register(new FakeKeyedFilterOperator())
                .register(new FakeCardinalityOperator());
        List<FeatureDefinition> definitions = List.of(
                FeatureDefinition.builder()
                        .name("seq")
                        .role(FeatureRole.RAW)
                        .dataType(DataType.STRING)
                        .addEntityScope(EntityScope.USER)
                        .sourceBinding("seq")
                        .declaredValueShape(ValueShape.SEQUENCE)
                        .build(),
                FeatureDefinition.raw("cand_key", DataType.STRING, EntityScope.ITEM, null),
                FeatureDefinition.builder()
                        .name("filtered")
                        .role(FeatureRole.DERIVED)
                        .dataType(DataType.OBJECT)
                        .expressionContent("myfilter(seq, cand_key)")
                        .declaredValueShape(ValueShape.SEQUENCE)
                        .defaultValue(Map.of("fallback", true))
                        .outputPolicy(OutputPolicy.INTERNAL_ONLY)
                        .build(),
                FeatureDefinition.derived(
                        "result", DataType.INT, "myagg(filtered)", OutputPolicy.OUTPUT));

        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry)
                .build(definitions, Set.of("result"));
        OptimizedLogicalPlan optimized = new LogicalDagOptimizer(registry).analyze(dag);
        String aggregateNodeId = dag.featureOutput("result").producerNodeId();

        Optional<PhysicalRewrite> match = new CountAfterKeyedSequenceFilterRule()
                .match(optimized, aggregateNodeId, ExecutionEnvironment.ONLINE, registry);

        assertFalse("融合不得跳过带 dft 的衍生特征边界", match.isPresent());
    }

    @Test
    public void registryKeepsRewriteAfterExecutorGainsFailureRecovery() {
        Map<String, PhysicalRewrite> failFastRewrites = selectRewrites(false);
        assertTrue("无 dft 路径仍可使用既有融合", !failFastRewrites.isEmpty());
        assertTrue(failFastRewrites.values().iterator().next().failureRecoverySupported());

        Map<String, PhysicalRewrite> recoveredRewrites = selectRewrites(true);
        assertTrue("带 dft 的路径可使用已支持失败恢复的融合", !recoveredRewrites.isEmpty());
    }

    private static Optional<PhysicalRewrite> matchRewrite(EntityScope sequenceScope) {
        OperatorRegistry registry = new OperatorRegistry()
                .register(new FakeKeyedFilterOperator())
                .register(new FakeCardinalityOperator());

        List<FeatureDefinition> definitions = List.of(
                FeatureDefinition.builder()
                        .name("seq")
                        .role(FeatureRole.RAW)
                        .dataType(DataType.STRING)
                        .addEntityScope(sequenceScope)
                        .sourceBinding("seq")
                        .declaredValueShape(ValueShape.SEQUENCE)
                        .build(),
                FeatureDefinition.raw("cand_key", DataType.STRING, EntityScope.ITEM, null),
                FeatureDefinition.derived(
                        "result", DataType.INT, "myagg(myfilter(seq, cand_key))", OutputPolicy.OUTPUT));

        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry)
                .build(definitions, Set.of("result"));
        OptimizedLogicalPlan optimized = new LogicalDagOptimizer(registry).analyze(dag);
        String aggregateNodeId = dag.featureOutput("result").producerNodeId();

        return new CountAfterKeyedSequenceFilterRule()
                .match(optimized, aggregateNodeId, ExecutionEnvironment.ONLINE, registry);
    }

    private static Map<String, PhysicalRewrite> selectRewrites(boolean withDefault) {
        OperatorRegistry registry = new OperatorRegistry()
                .register(new FakeKeyedFilterOperator())
                .register(new FakeCardinalityOperator());
        FeatureDefinition.Builder result = FeatureDefinition.builder()
                .name("result")
                .role(FeatureRole.DERIVED)
                .dataType(DataType.INT)
                .expressionContent("myagg(myfilter(seq, cand_key))")
                .outputPolicy(OutputPolicy.OUTPUT);
        if (withDefault) result.defaultValue(-1);
        List<FeatureDefinition> definitions = List.of(
                FeatureDefinition.builder()
                        .name("seq")
                        .role(FeatureRole.RAW)
                        .dataType(DataType.STRING)
                        .addEntityScope(EntityScope.USER)
                        .sourceBinding("seq")
                        .declaredValueShape(ValueShape.SEQUENCE)
                        .build(),
                FeatureDefinition.raw("cand_key", DataType.STRING, EntityScope.ITEM, null),
                result.build());
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry)
                .build(definitions, Set.of("result"));
        OptimizedLogicalPlan optimized = new LogicalDagOptimizer(registry).analyze(dag);

        return new PhysicalRewriteRegistry()
                .register(new CountAfterKeyedSequenceFilterRule())
                .select(optimized, ExecutionEnvironment.ONLINE, registry);
    }

    private static final class FakeKeyedFilterOperator implements OperatorDefinition {
        @Override public String name() { return "myfilter"; }
        @Override public int minArguments() { return 2; }
        @Override public int maxArguments() { return 2; }
        @Override public boolean deterministic() { return true; }
        @Override public boolean supportsSequenceView() { return false; }
        @Override public boolean sideEffectFree() { return true; }

        @Override
        public List<OperatorSemantic> semantics() {
            return List.of(new KeyedSequenceFilterSemantic(0, 1, SequenceKeyDomains.INDUSTRY));
        }

        @Override
        public OperatorInference infer(List<OperatorInputMetadata> inputs) {
            Set<EntityScope> scopes = new LinkedHashSet<>();
            for (OperatorInputMetadata input : inputs) scopes.addAll(input.entityScopes());
            return new OperatorInference(DataType.OBJECT, scopes, ValueShape.SEQUENCE);
        }

        @Override
        public Object evaluate(List<Object> arguments) {
            throw new UnsupportedOperationException("not exercised by this test");
        }
    }

    private static final class FakeCardinalityOperator implements OperatorDefinition {
        @Override public String name() { return "myagg"; }
        @Override public int minArguments() { return 1; }
        @Override public int maxArguments() { return 1; }
        @Override public boolean deterministic() { return true; }
        @Override public boolean supportsSequenceView() { return false; }
        @Override public boolean sideEffectFree() { return true; }

        @Override
        public List<OperatorSemantic> semantics() {
            return List.of(new SequenceCardinalitySemantic(0));
        }

        @Override
        public OperatorInference infer(List<OperatorInputMetadata> inputs) {
            return new OperatorInference(DataType.INT, inputs.get(0).entityScopes(), ValueShape.SCALAR);
        }

        @Override
        public Object evaluate(List<Object> arguments) {
            throw new UnsupportedOperationException("not exercised by this test");
        }
    }
}
