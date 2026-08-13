package com.example.featuredag.runtime;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.FeatureDefinition;
import com.example.featuredag.definition.OutputPolicy;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.operator.OperatorDefinition;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.operator.OperatorSequence;
import com.example.featuredag.operator.SequenceKeyDomains;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.physical.ExecutorType;
import com.example.featuredag.physical.PhysicalNode;
import com.example.featuredag.physical.PhysicalPlan;
import com.example.featuredag.physical.PhysicalPlanner;
import com.example.featuredag.physical.SequenceViewInputMode;
import com.example.featuredag.planning.LogicalDagOptimizer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 序列视图的规划与运行时输入适配（JUnit 4）：
 * DIRECT/MATERIALIZE 固化、Single/Batch 边界适配、Native Batch 物化复用、
 * 以及序列索引 key 归一化对称。
 */
public final class SequenceViewRuntimeTest {
    @Test
    public void plannedInputModeDirectAndMaterialize() {
        Map<String, Object> first = Map.of(
                "itemId", "item-1", "industryId", "a", "timestamp", 1L,
                "eventType", "view", "value", 1.0);
        Map<String, Object> second = Map.of(
                "itemId", "item-2", "industryId", "b", "timestamp", 2L,
                "eventType", "click", "value", 2.0);
        Map<String, Object> third = Map.of(
                "itemId", "item-3", "industryId", "c", "timestamp", 3L,
                "eventType", "view", "value", 3.0);
        SequenceBlock block = new SequenceBlock(
                "sequence-view-runtime", 1L, List.of(first, second, third));
        SequenceView view = SequenceView.slice(block, 1, 3);
        SequenceView otherView = SequenceView.slice(block, 0, 1);

        OperatorRegistry directRegistry = OperatorRegistry.standard()
                .register(new SequenceInputProbeOperator("sequence_view_direct_probe", true));
        PhysicalPlan directPlan = sequenceProbePlan(
                directRegistry, "sequence_view_direct_probe", "direct_result");
        assertEquals(
                SequenceViewInputMode.DIRECT,
                sequenceMode(directPlan, "sequence_view_direct_probe"));
        ExecutionResult directResult = new DagRuntime(directRegistry).execute(
                directPlan,
                ExecutionContext.offlineRow("direct-view", Map.of("events", view)));
        SequenceProbeResult directProbe = (SequenceProbeResult) directResult
                .feature("direct_result").raw();
        assertSame(view, directProbe.argument());
        assertTrue(directProbe.argument() instanceof OperatorSequence);

        OperatorRegistry materializeRegistry = OperatorRegistry.standard()
                .register(new SequenceInputProbeOperator("sequence_view_list_probe", false));
        PhysicalPlan materializePlan = sequenceProbePlan(
                materializeRegistry, "sequence_view_list_probe", "materialized_result");
        assertEquals(
                SequenceViewInputMode.MATERIALIZE,
                sequenceMode(materializePlan, "sequence_view_list_probe"));
        ExecutionResult materializeResult = new DagRuntime(materializeRegistry).execute(
                materializePlan,
                ExecutionContext.offlineRow("materialized-view", Map.of("events", view)));
        SequenceProbeResult materializedProbe = (SequenceProbeResult) materializeResult
                .feature("materialized_result").raw();
        assertTrue(materializedProbe.argument() instanceof List<?>);
        assertEquals(List.of(second, third), materializedProbe.argument());
        assertThrows(
                UnsupportedOperationException.class,
                () -> ((List<Object>) materializedProbe.argument()).add(first));

        ExecutionResult batchResult = new DagRuntime(materializeRegistry).execute(
                materializePlan,
                ExecutionContext.offlineBatch(
                        "materialized-view-batch",
                        List.of(
                                Map.of("events", view),
                                Map.of("events", view),
                                Map.of("events", otherView))));
        OfflineBatchValue batchValues =
                (OfflineBatchValue) batchResult.feature("materialized_result");
        SequenceProbeResult firstRow = (SequenceProbeResult) batchValues.valueAt(0);
        SequenceProbeResult secondRow = (SequenceProbeResult) batchValues.valueAt(1);
        SequenceProbeResult thirdRow = (SequenceProbeResult) batchValues.valueAt(2);
        assertSame(
                "The same view should be materialized once within one batch group",
                firstRow.argument(),
                secondRow.argument());
        assertEquals(List.of(second, third), firstRow.argument());
        assertEquals(List.of(first), thirdRow.argument());
        assertNotSame(
                "Different views over one block must not share materialized values",
                thirdRow.argument(),
                firstRow.argument());
    }

    @Test
    public void directSequenceViewRuntimeBatch() {
        OperatorRegistry registry = OperatorRegistry.standard();
        String outputFeature = "distinct_result";
        List<FeatureDefinition> definitions = List.of(
                FeatureDefinition.raw(
                        "events", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                FeatureDefinition.derived(
                        outputFeature,
                        DataType.INT,
                        "count_distinct(events)",
                        OutputPolicy.OUTPUT));
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry)
                .build(definitions, Set.of(outputFeature));
        PhysicalPlan plan = new PhysicalPlanner(registry).plan(
                new LogicalDagOptimizer(registry).analyze(dag),
                ExecutionEnvironment.OFFLINE,
                "direct-sequence-view-batch");
        assertEquals(
                SequenceViewInputMode.DIRECT,
                sequenceMode(plan, "count_distinct"));

        Map<String, Object> first = Map.of(
                "itemId", "item-1", "industryId", "a", "timestamp", 1L,
                "eventType", "view", "value", 1.0);
        Map<String, Object> second = Map.of(
                "itemId", "item-2", "industryId", "b", "timestamp", 2L,
                "eventType", "view", "value", 2.0);
        SequenceBlock block = new SequenceBlock(
                "direct-sequence-view-batch", 1L, List.of(first, first, second));
        SequenceView repeatedFirst = SequenceView.slice(block, 0, 2);
        SequenceView firstAndSecond = SequenceView.slice(block, 1, 3);
        ExecutionResult result = new DagRuntime(registry).execute(
                plan,
                ExecutionContext.offlineBatch(
                        "direct-sequence-view-batch",
                        List.of(
                                Map.of("events", repeatedFirst),
                                Map.of("events", firstAndSecond))));
        OfflineBatchValue values = (OfflineBatchValue) result.feature(outputFeature);
        assertEquals(List.of(1, 2), values.values());
    }

    @Test
    public void nativeBatchSequenceViewMaterialization() {
        OperatorRegistry registry = OperatorRegistry.standard();
        String outputFeature = "delta_result";
        List<FeatureDefinition> definitions = List.of(
                FeatureDefinition.builder()
                        .name("numbers")
                        .role(com.example.featuredag.definition.FeatureRole.RAW)
                        .dataType(DataType.DOUBLE)
                        .addEntityScope(EntityScope.USER)
                        .sourceBinding("numbers")
                        .declaredValueShape(ValueShape.SEQUENCE)
                        .build(),
                FeatureDefinition.derived(
                        outputFeature,
                        DataType.DOUBLE,
                        "calc_delta_seq(numbers, 1)",
                        OutputPolicy.OUTPUT));
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry)
                .build(definitions, Set.of(outputFeature));
        PhysicalPlan plan = new PhysicalPlanner(registry).plan(
                new LogicalDagOptimizer(registry).analyze(dag),
                ExecutionEnvironment.OFFLINE,
                "native-sequence-view-materialization");
        assertEquals(
                SequenceViewInputMode.MATERIALIZE,
                sequenceMode(plan, "calc_delta_seq"));

        TestOperatorSequence repeated = new TestOperatorSequence(List.of(2.0, 4.0));
        TestOperatorSequence equalButDistinct = new TestOperatorSequence(List.of(2.0, 4.0));
        ExecutionResult result = new DagRuntime(registry).execute(
                plan,
                ExecutionContext.offlineBatch(
                        "native-sequence-view-materialization",
                        List.of(
                                Map.of("numbers", repeated),
                                Map.of("numbers", repeated),
                                Map.of("numbers", equalButDistinct))));
        OfflineBatchValue values = (OfflineBatchValue) result.feature(outputFeature);
        assertEquals(List.of(1.0, 3.0), values.valueAt(0));
        assertSame(
                "Native Batch should reuse one materialized sequence identity",
                values.valueAt(0),
                values.valueAt(1));
        assertNotSame(
                "Different sequence identities must not collide",
                values.valueAt(0),
                values.valueAt(2));
    }

    @Test
    public void sequenceIndexKeyNormalizationSymmetric() {
        // 索引 key 与查询 key 共用同一归一化器：Integer(1) 字段值与查询 "1" 命中同一索引键。
        Map<String, Object> event = Map.of("industryId", 1, "value", 1.0);
        SequenceBlock block = new SequenceBlock(
                "index-normalization", 1L, List.of(event));
        SequenceIndexRegistry registry = SequenceIndexRegistry.standard();
        SequenceIndexProvider provider = registry.require(SequenceKeyDomains.INDUSTRY);
        IndexValue index = provider.build(block);
        Object normalizedQuery = provider.normalizeQueryKey(1);
        assertEquals("1", normalizedQuery);
        assertEquals(
                "Integer(1) index key should match normalized query key \"1\"",
                1,
                index.count(normalizedQuery));
    }

    private static PhysicalPlan sequenceProbePlan(
            OperatorRegistry registry,
            String operatorName,
            String outputFeature) {
        List<FeatureDefinition> definitions = List.of(
                FeatureDefinition.raw(
                        "events", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                FeatureDefinition.derived(
                        outputFeature,
                        DataType.OBJECT,
                        operatorName + "(events)",
                        OutputPolicy.OUTPUT));
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry)
                .build(definitions, Set.of(outputFeature));
        return new PhysicalPlanner(registry).plan(
                new LogicalDagOptimizer(registry).analyze(dag),
                ExecutionEnvironment.OFFLINE,
                "sequence-view-probe-" + operatorName);
    }

    private static SequenceViewInputMode sequenceMode(
            PhysicalPlan plan,
            String operatorName) {
        PhysicalNode node = plan.nodes().stream()
                .filter(candidate -> candidate.executorType() == ExecutorType.GENERIC_OPERATOR)
                .filter(candidate -> operatorName.equals(
                        candidate.executorConfig().get("operatorName")))
                .findFirst()
                .orElseThrow();
        return (SequenceViewInputMode) node.executorConfig().get("sequenceViewInputMode");
    }

    private record SequenceProbeResult(Object argument) {}

    private static final class SequenceInputProbeOperator implements OperatorDefinition {
        private final String name;
        private final boolean supportsSequenceView;

        private SequenceInputProbeOperator(String name, boolean supportsSequenceView) {
            this.name = name;
            this.supportsSequenceView = supportsSequenceView;
        }

        @Override public String name() { return name; }
        @Override public int minArguments() { return 1; }
        @Override public int maxArguments() { return 1; }
        @Override public boolean deterministic() { return true; }
        @Override public boolean supportsSequenceView() { return supportsSequenceView; }
        @Override public boolean sideEffectFree() { return true; }

        @Override
        public OperatorInference infer(List<OperatorInputMetadata> inputs) {
            return new OperatorInference(
                    DataType.OBJECT,
                    inputs.get(0).entityScopes(),
                    ValueShape.SCALAR);
        }

        @Override
        public Object evaluate(List<Object> arguments) {
            return new SequenceProbeResult(arguments.get(0));
        }
    }

    private static final class TestOperatorSequence implements OperatorSequence {
        private final List<Object> values;

        private TestOperatorSequence(List<?> values) {
            this.values = new ArrayList<Object>(values);
        }

        @Override public int size() { return values.size(); }
        @Override public Object elementAt(int index) { return values.get(index); }
        @Override public OperatorSequence filterByColumn(String column, Object value) { return this; }
    }
}
