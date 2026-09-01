package com.example.featuredag.operator;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.FeatureDefinition;
import com.example.featuredag.definition.OutputPolicy;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.DagBuildException;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.logical.OperatorNode;
import com.example.featuredag.operator.builtin.InitialBusinessOperators;
import com.example.featuredag.planning.LogicalDagOptimizer;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.physical.PhysicalPlan;
import com.example.featuredag.physical.PhysicalPlanner;
import com.example.featuredag.runtime.DagRuntime;
import com.example.featuredag.runtime.ExecutionContext;
import com.example.featuredag.runtime.ExecutionResult;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 数值转换（to_int / to_bigint）与极值（min / max）算子的注册、推断、执行、
 * 异常和 Batch 路由测试（JUnit 4）。四个算子均不提供原生 Batch，
 * 由 SingleLoopBatchOperatorKernel 逐行适配并保持行序。
 */
public final class NumericCastAndExtremeOperatorsTest {
    @Test
    public void registryManifestAndMetadata() {
        assertEquals(23, InitialBusinessOperators.definitions().size());
        OperatorRegistry registry = OperatorRegistry.standard();
        for (String name : new String[] {"to_int", "to_bigint", "min", "max"}) {
            OperatorDefinition definition = registry.require(name);
            assertTrue(name + " deterministic", definition.deterministic());
            assertTrue(name + " sideEffectFree", definition.sideEffectFree());
            assertEquals(
                    name + " batch kernel kind",
                    BatchKernelKind.SCALAR_ADAPTER,
                    registry.batchKernelKind(name));
        }
        for (String name : new String[] {"to_int", "to_bigint", "min", "max"}) {
            assertTrue(name + " sequence view", registry.require(name).supportsSequenceView());
        }
        assertEquals(1, registry.require("to_int").minArguments());
        assertEquals(1, registry.require("to_int").maxArguments());
        assertEquals(1, registry.require("to_bigint").minArguments());
        assertEquals(1, registry.require("to_bigint").maxArguments());
        assertEquals(2, registry.require("min").minArguments());
        assertEquals(Integer.MAX_VALUE, registry.require("min").maxArguments());
        assertEquals(2, registry.require("max").minArguments());
        assertEquals(Integer.MAX_VALUE, registry.require("max").maxArguments());
    }

    @Test
    public void castInference() {
        OperatorRegistry registry = OperatorRegistry.standard();
        TestInput intInput = new TestInput(
                DataType.INT, Set.of(EntityScope.USER), ValueShape.SCALAR);
        TestInput doubleInput = new TestInput(
                DataType.DOUBLE, Set.of(EntityScope.ITEM), ValueShape.SCALAR);

        OperatorInference toInt = registry.infer("to_int", List.of(intInput));
        assertEquals(DataType.INT, toInt.outputType());
        assertEquals(ValueShape.SCALAR, toInt.valueShape());
        assertEquals(Set.of(EntityScope.USER), toInt.entityScopes());

        OperatorInference toBigint = registry.infer("to_bigint", List.of(doubleInput));
        assertEquals(DataType.BIGINT, toBigint.outputType());
        assertEquals(ValueShape.SCALAR, toBigint.valueShape());
        assertEquals(Set.of(EntityScope.ITEM), toBigint.entityScopes());

        TestInput doubleSequence = new TestInput(
                DataType.DOUBLE, Set.of(EntityScope.USER), ValueShape.SEQUENCE);
        OperatorInference sequenceToInt = registry.infer("to_int", List.of(doubleSequence));
        assertEquals(DataType.INT, sequenceToInt.outputType());
        assertEquals(ValueShape.SEQUENCE, sequenceToInt.valueShape());
        assertEquals(Set.of(EntityScope.USER), sequenceToInt.entityScopes());

        TestInput candidateVector = new TestInput(
                DataType.INT, Set.of(EntityScope.ITEM), ValueShape.CANDIDATE_VECTOR);
        assertEquals(
                ValueShape.SCALAR,
                registry.infer("to_bigint", List.of(candidateVector)).valueShape());

        TestInput eventSequence = new TestInput(
                DataType.EVENT_SEQUENCE, Set.of(EntityScope.USER), ValueShape.SEQUENCE);
        for (String name : new String[] {"to_int", "to_bigint"}) {
            IllegalArgumentException rejected = assertThrows(
                    IllegalArgumentException.class,
                    () -> registry.infer(name, List.of(eventSequence)));
            assertTrue(rejected.getMessage().contains("event sequences"));
        }

        TestInput stringSequence = new TestInput(
                DataType.STRING, Set.of(EntityScope.USER), ValueShape.SEQUENCE);
        OperatorInference stringToInt = registry.infer(
                "to_int", List.of(stringSequence));
        assertEquals(DataType.INT, stringToInt.outputType());
        assertEquals(ValueShape.SEQUENCE, stringToInt.valueShape());

        TestInput booleanInput = new TestInput(
                DataType.BOOLEAN, Set.of(EntityScope.USER), ValueShape.SCALAR);
        IllegalArgumentException nonNumeric = assertThrows(
                IllegalArgumentException.class,
                () -> registry.infer("to_int", List.of(booleanInput)));
        assertTrue(nonNumeric.getMessage().contains("numeric or decimal-string input"));
    }

    @Test
    public void castEvaluation() {
        OperatorRegistry registry = OperatorRegistry.standard();
        // to_int 固定产出 Integer 载体：整数直通、小数向零截断、Long 收窄。
        assertEquals(Integer.valueOf(7), registry.evaluate("to_int", List.of(7)));
        assertEquals(Integer.valueOf(3), registry.evaluate("to_int", List.of(3.7)));
        assertEquals(Integer.valueOf(-3), registry.evaluate("to_int", List.of(-3.7)));
        assertEquals(Integer.valueOf(5), registry.evaluate("to_int", List.of(5L)));
        assertEquals(
                Integer.valueOf(Integer.MAX_VALUE),
                registry.evaluate("to_int", List.of(2147483647L)));
        // to_bigint 固定产出 Long 载体：Integer 拓宽、大整数保持精确。
        assertEquals(Long.valueOf(5L), registry.evaluate("to_bigint", List.of(5)));
        assertEquals(Long.valueOf(3L), registry.evaluate("to_bigint", List.of(3.7)));
        assertEquals(Long.valueOf(-3L), registry.evaluate("to_bigint", List.of(-3.7)));
        assertEquals(
                Long.valueOf(9007199254740993L),
                registry.evaluate("to_bigint", List.of(9007199254740993L)));

        assertEquals(
                List.of(3, 5, -2),
                registry.evaluate("to_int", List.of(List.of(3.7, 5, -2.9))));
        assertEquals(
                List.of(3L, 5L, -2L),
                registry.evaluate("to_bigint", List.of(List.of(3.7, 5, -2.9))));
        assertEquals(
                List.of(),
                registry.evaluate("to_int", List.of(List.of())));

        TestOperatorSequence sequenceView = new TestOperatorSequence(List.of(1.9, 2.1));
        assertEquals(
                List.of(1, 2),
                registry.evaluate("to_int", List.of(sequenceView)));
    }

    @Test
    public void castFailures() {
        OperatorRegistry registry = OperatorRegistry.standard();
        IllegalArgumentException intOverflow = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("to_int", List.of(2147483648L)));
        assertTrue(intOverflow.getMessage().contains("overflow"));
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("to_int", List.of(2.5e9)));

        IllegalArgumentException longOverflow = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("to_bigint", List.of(new BigDecimal("9223372036854775808"))));
        assertTrue(longOverflow.getMessage().contains("overflow"));
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("to_bigint", List.of(1.0e19)));

        // 非有限值、非法字符串与非数值输入在求值期拒绝。
        IllegalArgumentException notFinite = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("to_int", List.of(Double.NaN)));
        assertTrue(notFinite.getMessage().contains("finite"));
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("to_bigint", List.of("not-a-number")));
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("to_int", Arrays.<Object>asList((Object) null)));

        IllegalArgumentException sequenceOverflow = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate(
                        "to_int", List.of(List.of(1.0, 2147483648L))));
        assertTrue(sequenceOverflow.getMessage().contains("sequence index 1"));
        assertTrue(sequenceOverflow.getMessage().contains("overflow"));
    }

    @Test
    public void extremeInference() {
        OperatorRegistry registry = OperatorRegistry.standard();
        TestInput intInput = new TestInput(
                DataType.INT, Set.of(EntityScope.USER), ValueShape.SCALAR);
        TestInput bigintInput = new TestInput(
                DataType.BIGINT, Set.of(EntityScope.USER), ValueShape.SCALAR);
        TestInput doubleInput = new TestInput(
                DataType.DOUBLE, Set.of(EntityScope.ITEM), ValueShape.SCALAR);

        // 宽度按 DOUBLE > BIGINT > INT 提升；实体域取输入并集。
        assertEquals(
                DataType.INT,
                registry.infer("min", List.of(intInput, intInput)).outputType());
        assertEquals(
                DataType.BIGINT,
                registry.infer("min", List.of(intInput, bigintInput)).outputType());
        OperatorInference promoted = registry.infer("max", List.of(intInput, doubleInput));
        assertEquals(DataType.DOUBLE, promoted.outputType());
        assertEquals(Set.of(EntityScope.USER, EntityScope.ITEM), promoted.entityScopes());

        // 参数个数在注册表层校验：min/max 至少两个参数。
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.infer("min", List.of(intInput)));
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("max", List.of(intInput)));

        TestInput eventSequence = new TestInput(
                DataType.EVENT_SEQUENCE, Set.of(EntityScope.USER), ValueShape.SEQUENCE);
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> registry.infer("min", List.of(eventSequence, intInput)));
        assertTrue(rejected.getMessage().contains("event sequences"));
    }

    @Test
    public void extremeEvaluation() {
        OperatorRegistry registry = OperatorRegistry.standard();
        assertEquals(Integer.valueOf(1), registry.evaluate("min", List.of(3, 1, 2)));
        assertEquals(Integer.valueOf(3), registry.evaluate("max", List.of(3, 1, 2)));
        assertEquals(Integer.valueOf(-5), registry.evaluate("min", List.of(-5, -2.5)));
        assertEquals(Double.valueOf(-2.5), registry.evaluate("max", List.of(-5, -2.5)));

        // 返回胜出参数的原数值载体；INT 与 DOUBLE 混比时 INT 可能胜出。
        assertEquals(Integer.valueOf(1), registry.evaluate("min", List.of(1, 2.5)));
        assertEquals(Double.valueOf(2.5), registry.evaluate("max", List.of(1, 2.5)));

        // 精确十进制比较：9007199254740993 无法被 double 精确表示，
        // 若按 double 比较两者判等、min/max 都退化为返回最左输入；BigDecimal 比较能分辨大小。
        assertEquals(
                Double.valueOf(9007199254740992.0),
                registry.evaluate("min", List.of(9007199254740993L, 9007199254740992.0)));
        assertEquals(
                Long.valueOf(9007199254740993L),
                registry.evaluate("max", List.of(9007199254740992.0, 9007199254740993L)));

        // 相等时保留最左输入的原载体：min(2, 2.0) 返回 Integer 而非 Double。
        assertEquals(Integer.valueOf(2), registry.evaluate("min", List.of(2, 2.0)));

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("min", Arrays.<Object>asList(1, "x")));
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("max", Arrays.<Object>asList(1, null)));
        IllegalArgumentException notFinite = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("max", Arrays.<Object>asList(1, Double.NaN)));
        assertTrue(notFinite.getMessage().contains("finite"));
    }

    @Test
    public void batchRoutingUsesScalarAdapter() {
        OperatorRegistry registry = OperatorRegistry.standard();
        BatchOperatorCall minCall = new BatchOperatorCall(
                new FixedBatchLayout(BatchDomain.OFFLINE_ROW, 3),
                List.of(
                        new ListBatchColumn(List.of(3, 1, 2)),
                        new ListBatchColumn(List.of(4, 2, 9))));
        BatchOperatorResult minResult =
                registry.evaluateBatch("min", minCall, BatchKernelKind.SCALAR_ADAPTER);
        assertEquals(3, minResult.values().size());
        assertEquals(Integer.valueOf(3), minResult.values().valueAt(0));
        assertEquals(Integer.valueOf(1), minResult.values().valueAt(1));
        assertEquals(Integer.valueOf(2), minResult.values().valueAt(2));

        BatchOperatorCall toIntCall = new BatchOperatorCall(
                new FixedBatchLayout(BatchDomain.OFFLINE_ROW, 3),
                List.of(new ListBatchColumn(List.of(3.7, 5, -2.9))));
        BatchOperatorResult toIntResult =
                registry.evaluateBatch("to_int", toIntCall, BatchKernelKind.SCALAR_ADAPTER);
        assertEquals(Integer.valueOf(3), toIntResult.values().valueAt(0));
        assertEquals(Integer.valueOf(5), toIntResult.values().valueAt(1));
        assertEquals(Integer.valueOf(-2), toIntResult.values().valueAt(2));

        BatchOperatorCall toIntSequenceCall = new BatchOperatorCall(
                new FixedBatchLayout(BatchDomain.OFFLINE_ROW, 2),
                List.of(new ListBatchColumn(List.of(
                        List.of(3.7, -2.9),
                        List.of(5L)))));
        BatchOperatorResult toIntSequenceResult = registry.evaluateBatch(
                "to_int", toIntSequenceCall, BatchKernelKind.SCALAR_ADAPTER);
        assertEquals(List.of(3, -2), toIntSequenceResult.values().valueAt(0));
        assertEquals(List.of(5), toIntSequenceResult.values().valueAt(1));

        // 默认路由读取注册内核种类，同样落在 SCALAR_ADAPTER 上。
        assertEquals(
                Integer.valueOf(3), registry.evaluateBatch("min", minCall).values().valueAt(0));

        // 未注册原生 Batch 的算子被物理计划声明为 NATIVE 时必须 fail-fast。
        assertThrows(
                IllegalStateException.class,
                () -> registry.evaluateBatch("min", minCall, BatchKernelKind.NATIVE));

        // 批失败携带紧凑行号，供运行时映射回原请求/候选位置。
        BatchOperatorCall badCall = new BatchOperatorCall(
                new FixedBatchLayout(BatchDomain.OFFLINE_ROW, 2),
                List.of(
                        new ListBatchColumn(List.of(1, 5)),
                        new ListBatchColumn(Arrays.<Object>asList("not-a-number", 2))));
        BatchOperatorEvaluationException failure = assertThrows(
                BatchOperatorEvaluationException.class,
                () -> registry.evaluateBatch("min", badCall, BatchKernelKind.SCALAR_ADAPTER));
        assertEquals(0, failure.rowIndex());

        BatchOperatorCall badSequenceCall = new BatchOperatorCall(
                new FixedBatchLayout(BatchDomain.OFFLINE_ROW, 2),
                List.of(new ListBatchColumn(List.of(
                        List.of(1.0),
                        List.of(2.0, 2147483648L)))));
        BatchOperatorEvaluationException sequenceFailure = assertThrows(
                BatchOperatorEvaluationException.class,
                () -> registry.evaluateBatch(
                        "to_int", badSequenceCall, BatchKernelKind.SCALAR_ADAPTER));
        assertEquals(1, sequenceFailure.rowIndex());
        assertTrue(sequenceFailure.getCause().getMessage().contains("sequence index 1"));
    }

    @Test
    public void dagExpressionBuildAndInfer() {
        List<FeatureDefinition> definitions = List.of(
                FeatureDefinition.raw("score", DataType.DOUBLE, EntityScope.USER, 0.0),
                FeatureDefinition.derived(
                        "score_int", DataType.INT, "to_int(score)", OutputPolicy.OUTPUT),
                FeatureDefinition.derived(
                        "score_bigint", DataType.BIGINT, "to_bigint(score)", OutputPolicy.OUTPUT),
                FeatureDefinition.derived(
                        "score_cap", DataType.DOUBLE, "min(score, 5)", OutputPolicy.OUTPUT));
        LogicalDag dag = new LogicalDagBuilder(
                new ExpressionParser(), OperatorRegistry.standard())
                .build(definitions, Set.of("score_int", "score_bigint", "score_cap"));

        OperatorNode castNode =
                (OperatorNode) dag.node(dag.featureOutput("score_int").producerNodeId());
        assertEquals("to_int", castNode.operatorName());
        assertEquals(DataType.INT, castNode.outputType());

        OperatorNode bigintNode =
                (OperatorNode) dag.node(dag.featureOutput("score_bigint").producerNodeId());
        assertEquals("to_bigint", bigintNode.operatorName());
        assertEquals(DataType.BIGINT, bigintNode.outputType());

        OperatorNode capNode =
                (OperatorNode) dag.node(dag.featureOutput("score_cap").producerNodeId());
        assertEquals("min", capNode.operatorName());
        // min(DOUBLE, INT 字面量) 按宽度上界提升为 DOUBLE。
        assertEquals(DataType.DOUBLE, capNode.outputType());

        // C6：声明 INT 但推断为 DOUBLE 时构图期失败（INT→DOUBLE 的放宽只对声明方向生效）。
        List<FeatureDefinition> mismatched = List.of(
                FeatureDefinition.raw("score", DataType.DOUBLE, EntityScope.USER, 0.0),
                FeatureDefinition.derived(
                        "bad_cap", DataType.INT, "min(score, 5)", OutputPolicy.OUTPUT));
        assertThrows(
                DagBuildException.class,
                () -> new LogicalDagBuilder(
                        new ExpressionParser(), OperatorRegistry.standard())
                        .build(mismatched, Set.of("bad_cap")));
    }

    @Test
    public void sequenceCastDagExecutesEndToEnd() {
        FeatureDefinition numbers = FeatureDefinition.builder()
                .name("numbers")
                .role(com.example.featuredag.definition.FeatureRole.RAW)
                .dataType(DataType.DOUBLE)
                .addEntityScope(EntityScope.USER)
                .sourceBinding("numbers")
                .declaredValueShape(ValueShape.SEQUENCE)
                .build();
        FeatureDefinition intNumbers = FeatureDefinition.builder()
                .name("int_numbers")
                .role(com.example.featuredag.definition.FeatureRole.DERIVED)
                .dataType(DataType.INT)
                .expressionContent("to_int(numbers)")
                .outputPolicy(OutputPolicy.OUTPUT)
                .declaredValueShape(ValueShape.SEQUENCE)
                .build();
        FeatureDefinition bigintNumbers = FeatureDefinition.builder()
                .name("bigint_numbers")
                .role(com.example.featuredag.definition.FeatureRole.DERIVED)
                .dataType(DataType.BIGINT)
                .expressionContent("to_bigint(numbers)")
                .outputPolicy(OutputPolicy.OUTPUT)
                .declaredValueShape(ValueShape.SEQUENCE)
                .build();

        OperatorRegistry registry = OperatorRegistry.standard();
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry)
                .build(
                        List.of(numbers, intNumbers, bigintNumbers),
                        Set.of("int_numbers", "bigint_numbers"));
        PhysicalPlan plan = new PhysicalPlanner(registry).plan(
                new LogicalDagOptimizer(registry).analyze(dag),
                ExecutionEnvironment.OFFLINE,
                "numeric-sequence-cast");
        ExecutionResult result = new DagRuntime(registry).execute(
                plan,
                ExecutionContext.offlineRow(
                        "numeric-sequence-cast",
                        Map.of("numbers", List.of(3.7, 5.0, -2.9))));

        assertEquals(List.of(3, 5, -2), result.feature("int_numbers").raw());
        assertEquals(List.of(3L, 5L, -2L), result.feature("bigint_numbers").raw());
    }

    /** 算子推断所需的最小输入元数据：类型、实体域和值形状。 */
    private record TestInput(
            DataType outputType,
            Set<EntityScope> entityScopes,
            ValueShape valueShape) implements OperatorInputMetadata {
        @Override
        public String sourceFeatureName() {
            return "test-source";
        }
    }

    private record FixedBatchLayout(
            BatchDomain domain,
            int rowCount) implements BatchLayout {
        @Override
        public int groupIndexAt(int rowIndex) {
            return domain == BatchDomain.ONLINE_CANDIDATE ? rowIndex / 2 : -1;
        }

        @Override
        public int indexInGroupAt(int rowIndex) {
            return domain == BatchDomain.ONLINE_CANDIDATE ? rowIndex % 2 : rowIndex;
        }
    }

    private static final class TestOperatorSequence implements OperatorSequence {
        private final List<?> values;

        private TestOperatorSequence(List<?> values) {
            this.values = List.copyOf(values);
        }

        @Override
        public int size() {
            return values.size();
        }

        @Override
        public Object elementAt(int index) {
            return values.get(index);
        }

        @Override
        public OperatorSequence filterByColumn(String column, Object value) {
            return this;
        }
    }
}
