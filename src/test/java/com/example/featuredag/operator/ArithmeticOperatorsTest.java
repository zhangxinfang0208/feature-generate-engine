package com.example.featuredag.operator;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.FeatureDefinition;
import com.example.featuredag.definition.FeatureRole;
import com.example.featuredag.definition.OutputPolicy;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.DagBuildException;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.logical.OperatorNode;
import com.example.featuredag.operator.builtin.InitialBusinessOperators;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.physical.PhysicalPlan;
import com.example.featuredag.physical.PhysicalPlanner;
import com.example.featuredag.planning.LogicalDagOptimizer;
import com.example.featuredag.runtime.DagRuntime;
import com.example.featuredag.runtime.ExecutionContext;
import com.example.featuredag.runtime.ExecutionResult;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 算术算子（add / sub / mul / div）的注册、推断、执行、异常和 Batch 路由测试（JUnit 4）。
 * 四个算子均不提供原生 Batch，由 SingleLoopBatchOperatorKernel 逐行适配并保持行序；
 * div 防除 0：分母为 0 时返回 0.0。末尾用同类目 ctr/cvr 场景做端到端验证。
 */
public final class ArithmeticOperatorsTest {
    @Test
    public void registryManifestAndMetadata() {
        assertEquals(21, InitialBusinessOperators.definitions().size());
        OperatorRegistry registry = OperatorRegistry.standard();
        for (String name : new String[] {"add", "sub", "mul", "div"}) {
            OperatorDefinition definition = registry.require(name);
            assertTrue(name + " deterministic", definition.deterministic());
            assertTrue(name + " sideEffectFree", definition.sideEffectFree());
            assertFalse(name + " sequence view", definition.supportsSequenceView());
            assertEquals(2, definition.minArguments());
            assertEquals(2, definition.maxArguments());
            assertEquals(
                    name + " batch kernel kind",
                    BatchKernelKind.SCALAR_ADAPTER,
                    registry.batchKernelKind(name));
        }
    }

    @Test
    public void arithmeticInference() {
        OperatorRegistry registry = OperatorRegistry.standard();
        TestInput intInput = new TestInput(
                DataType.INT, Set.of(EntityScope.USER), ValueShape.SCALAR);
        TestInput bigintInput = new TestInput(
                DataType.BIGINT, Set.of(EntityScope.USER), ValueShape.SCALAR);
        TestInput doubleInput = new TestInput(
                DataType.DOUBLE, Set.of(EntityScope.ITEM), ValueShape.SCALAR);

        // add/sub/mul：按 DOUBLE > BIGINT > INT 提升；实体域取输入并集。
        for (String name : new String[] {"add", "sub", "mul"}) {
            assertEquals(
                    DataType.INT,
                    registry.infer(name, List.of(intInput, intInput)).outputType());
            assertEquals(
                    DataType.BIGINT,
                    registry.infer(name, List.of(intInput, bigintInput)).outputType());
            OperatorInference promoted = registry.infer(name, List.of(intInput, doubleInput));
            assertEquals(DataType.DOUBLE, promoted.outputType());
            assertEquals(ValueShape.SCALAR, promoted.valueShape());
            assertEquals(Set.of(EntityScope.USER, EntityScope.ITEM), promoted.entityScopes());
        }

        // div：整型相除的截断商会吞掉比率信息，固定推断 DOUBLE。
        assertEquals(
                DataType.DOUBLE,
                registry.infer("div", List.of(intInput, intInput)).outputType());
        assertEquals(
                DataType.DOUBLE,
                registry.infer("div", List.of(doubleInput, intInput)).outputType());

        // 参数个数在注册表层校验：二元算子至少两个参数。
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.infer("add", List.of(intInput)));
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("div", List.of(1)));

        // 事件序列不做隐式数值投影：构图期即拒绝（C6）。
        TestInput eventSequence = new TestInput(
                DataType.EVENT_SEQUENCE, Set.of(EntityScope.USER), ValueShape.SEQUENCE);
        for (String name : new String[] {"add", "sub", "mul", "div"}) {
            IllegalArgumentException rejected = assertThrows(
                    IllegalArgumentException.class,
                    () -> registry.infer(name, List.of(eventSequence, intInput)));
            assertTrue(name + ": " + rejected.getMessage(),
                    rejected.getMessage().contains("event sequences"));
        }
    }

    @Test
    public void addSubMulEvaluation() {
        OperatorRegistry registry = OperatorRegistry.standard();
        // 整型载体统一产出 Long：结果由算子新建而非透传输入，宽载体避免 int 边界过早失败。
        assertEquals(Long.valueOf(5L), registry.evaluate("add", List.of(2, 3)));
        assertEquals(Long.valueOf(5L), registry.evaluate("add", List.of(2, 3L)));
        assertEquals(Long.valueOf(-3L), registry.evaluate("sub", List.of(2, 5)));
        assertEquals(Long.valueOf(12L), registry.evaluate("mul", List.of(3, 4)));

        // 任一浮点载体产出 Double；精确十进制消除 0.1 + 0.2 的二进制尾差。
        assertEquals(Double.valueOf(2.5), registry.evaluate("add", List.of(2, 0.5)));
        assertEquals(Double.valueOf(0.3), registry.evaluate("add", List.of(0.1, 0.2)));
        assertEquals(Double.valueOf(2.5), registry.evaluate("sub", List.of(5, 2.5)));
        assertEquals(Double.valueOf(10.0), registry.evaluate("mul", List.of(2.5, 4)));
        assertEquals(Double.valueOf(-7.0), registry.evaluate("mul", List.of(-2, 3.5)));

        // 精确十进制在 2^53 之上仍能区分大小：整数乘法不丢最低有效位。
        assertEquals(
                Long.valueOf(9007199254740993L),
                registry.evaluate("mul", List.of(9007199254740993L, 1L)));
    }

    @Test
    public void divisionEvaluationWithZeroProtection() {
        OperatorRegistry registry = OperatorRegistry.standard();
        // 整型相除不做截断商：10/4 = 2.5 而非 2。
        assertEquals(Double.valueOf(2.5), registry.evaluate("div", List.of(10, 4)));
        assertEquals(Double.valueOf(-2.5), registry.evaluate("div", List.of(-10, 4)));
        assertEquals(Double.valueOf(1.0 / 3.0), registry.evaluate("div", List.of(1, 3)));

        // 防除 0：分母为 0（含 0、0.0、-0.0、Long 0）一律返回 0.0，比率语义「无分母流量即比率为 0」。
        assertEquals(Double.valueOf(0.0), registry.evaluate("div", List.of(5, 0)));
        assertEquals(Double.valueOf(0.0), registry.evaluate("div", List.of(5, 0.0)));
        assertEquals(Double.valueOf(0.0), registry.evaluate("div", List.of(5, -0.0)));
        assertEquals(Double.valueOf(0.0), registry.evaluate("div", List.of(0, 0)));
        assertEquals(Double.valueOf(0.0), registry.evaluate("div", List.of(-7, 0L)));

        // 商超出 double 表示范围视为溢出，而非返回 ±Infinity。
        IllegalArgumentException quotientOverflow = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("div", List.of(1e300, 1e-300)));
        assertTrue(quotientOverflow.getMessage().contains("overflow"));
    }

    @Test
    public void arithmeticFailures() {
        OperatorRegistry registry = OperatorRegistry.standard();
        // 整型路径溢出直接失败不回绕（与 to_bigint 失败语义一致）。
        IllegalArgumentException sumOverflow = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("add", List.of(Long.MAX_VALUE, 1L)));
        assertTrue(sumOverflow.getMessage().contains("overflow"));
        IllegalArgumentException productOverflow = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("mul", List.of(1e300, 1e300)));
        assertTrue(productOverflow.getMessage().contains("overflow"));

        // 字符串不做隐式数值转换；null 与非有限值在求值期拒绝。
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("sub", Arrays.<Object>asList("x", 1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("add", Arrays.<Object>asList(null, 1)));
        IllegalArgumentException notFinite = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("div", Arrays.<Object>asList(1, Double.NaN)));
        assertTrue(notFinite.getMessage().contains("finite"));
    }

    @Test
    public void batchRoutingUsesScalarAdapter() {
        OperatorRegistry registry = OperatorRegistry.standard();
        BatchOperatorCall addCall = new BatchOperatorCall(
                new FixedBatchLayout(BatchDomain.OFFLINE_ROW, 3),
                List.of(
                        new ListBatchColumn(List.of(2, 10, 7)),
                        new ListBatchColumn(List.of(3, 4, 2.5))));
        BatchOperatorResult addResult =
                registry.evaluateBatch("add", addCall, BatchKernelKind.SCALAR_ADAPTER);
        assertEquals(3, addResult.values().size());
        assertEquals(Long.valueOf(5L), addResult.values().valueAt(0));
        assertEquals(Long.valueOf(14L), addResult.values().valueAt(1));
        assertEquals(Double.valueOf(9.5), addResult.values().valueAt(2));

        // 防除 0 在批路径逐行生效，行数与行序保持。
        BatchOperatorCall divCall = new BatchOperatorCall(
                new FixedBatchLayout(BatchDomain.OFFLINE_ROW, 3),
                List.of(
                        new ListBatchColumn(List.of(10, 1, 5)),
                        new ListBatchColumn(List.of(4, 0, 2))));
        BatchOperatorResult divResult =
                registry.evaluateBatch("div", divCall, BatchKernelKind.SCALAR_ADAPTER);
        assertEquals(Double.valueOf(2.5), divResult.values().valueAt(0));
        assertEquals(Double.valueOf(0.0), divResult.values().valueAt(1));
        assertEquals(Double.valueOf(2.5), divResult.values().valueAt(2));

        // 默认路由读取注册内核种类，同样落在 SCALAR_ADAPTER 上。
        assertEquals(Long.valueOf(5L), registry.evaluateBatch("add", addCall).values().valueAt(0));

        // 未注册原生 Batch 的算子被物理计划声明为 NATIVE 时必须 fail-fast。
        assertThrows(
                IllegalStateException.class,
                () -> registry.evaluateBatch("add", addCall, BatchKernelKind.NATIVE));

        // 批失败携带紧凑行号，供运行时映射回原请求/候选位置。
        BatchOperatorCall badCall = new BatchOperatorCall(
                new FixedBatchLayout(BatchDomain.OFFLINE_ROW, 2),
                List.of(
                        new ListBatchColumn(List.of(1, 5)),
                        new ListBatchColumn(Arrays.<Object>asList("not-a-number", 2))));
        BatchOperatorEvaluationException failure = assertThrows(
                BatchOperatorEvaluationException.class,
                () -> registry.evaluateBatch("add", badCall, BatchKernelKind.SCALAR_ADAPTER));
        assertEquals(0, failure.rowIndex());
    }

    @Test
    public void ctrCvrCaseEndToEnd() {
        // case1 同类目 ctr/cvr：创意/应用/媒体三个维度。上游按 365D 窗口收口并把事件序列
        // 投影为维度值序列；ctr/cvr 统一采用 div 内建防除 0 的推荐写法，不再手工 +epsilon。
        OperatorRegistry registry = OperatorRegistry.standard();
        List<FeatureDefinition> definitions = List.of(
                categorySequence("creative_industry_impressions"),
                categorySequence("creative_industry_clicks"),
                categorySequence("creative_industry_conversions"),
                categorySequence("app_category_impressions"),
                categorySequence("app_category_clicks"),
                categorySequence("app_category_conversions"),
                categorySequence("media_impressions"),
                categorySequence("media_clicks"),
                categorySequence("media_conversions"),
                ctrFeature("creative_industry_ctr", "creative_industry_clicks",
                        "creative_industry_impressions", "美妆"),
                cvrFeature("creative_industry_cvr", "creative_industry_conversions",
                        "creative_industry_clicks", "美妆"),
                ctrFeature("app_category_ctr", "app_category_clicks",
                        "app_category_impressions", "电商"),
                cvrFeature("app_category_cvr", "app_category_conversions",
                        "app_category_clicks", "电商"),
                ctrFeature("media_ctr", "media_clicks", "media_impressions", "抖音"),
                cvrFeature("media_cvr", "media_conversions", "media_clicks", "抖音"));

        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry)
                .build(definitions, Set.of(
                        "creative_industry_ctr", "creative_industry_cvr",
                        "app_category_ctr", "app_category_cvr",
                        "media_ctr", "media_cvr"));
        for (String output : new String[] {
                "creative_industry_ctr", "creative_industry_cvr",
                "app_category_ctr", "app_category_cvr", "media_ctr", "media_cvr"}) {
            assertEquals(output, DataType.BIGINT, dag.featureOutput(output).outputType());
        }

        // C5：同维度的点击长度子表达式在 ctr 的分子与 cvr 的分母间共享同一逻辑节点。
        String ctrClickLength = chainInput(dag, "app_category_ctr", 0, 0, 0);
        String cvrClickLength = chainInput(dag, "app_category_cvr", 0, 0, 1);
        assertEquals(ctrClickLength, cvrClickLength);
        assertEquals(
                "get_seq_length", ((OperatorNode) dag.node(ctrClickLength)).operatorName());

        PhysicalPlan plan = new PhysicalPlanner(registry).plan(
                new LogicalDagOptimizer(registry).analyze(dag),
                ExecutionEnvironment.OFFLINE,
                "ctr-cvr-case");

        // 创意行业「美妆」：曝光 4 / 点击 2 / 转化 1 → ctr=500、cvr=500；
        // 应用类目「电商」：曝光 3 / 点击 1 / 转化 1 → ctr=333（1/3 截断）、cvr=1000；
        // 媒体「抖音」：曝光 0 / 点击 0 / 转化 1 → ctr=0/0=0、cvr=1/0=0（div 防除 0）。
        ExecutionResult result = new DagRuntime(registry).execute(
                plan,
                ExecutionContext.offlineRow(
                        "ctr-cvr-case",
                        Map.of(
                                "creative_industry_impressions",
                                List.of("美妆", "美妆", "3C", "美妆", "美妆"),
                                "creative_industry_clicks", List.of("美妆", "美妆", "3C"),
                                "creative_industry_conversions", List.of("美妆"),
                                "app_category_impressions", List.of("电商", "电商", "游戏", "电商"),
                                "app_category_clicks", List.of("电商", "游戏"),
                                "app_category_conversions", List.of("电商"),
                                "media_impressions", List.of(),
                                "media_clicks", List.of(),
                                "media_conversions", List.of("抖音"))));
        assertEquals(Long.valueOf(500L), result.feature("creative_industry_ctr").raw());
        assertEquals(Long.valueOf(500L), result.feature("creative_industry_cvr").raw());
        assertEquals(Long.valueOf(333L), result.feature("app_category_ctr").raw());
        assertEquals(Long.valueOf(1000L), result.feature("app_category_cvr").raw());
        assertEquals(Long.valueOf(0L), result.feature("media_ctr").raw());
        assertEquals(Long.valueOf(0L), result.feature("media_cvr").raw());
    }

    @Test
    public void divTypeDeclarationMismatchRejectedByC6() {
        // div 固定推断 DOUBLE：声明 INT 时构图期失败（INT→DOUBLE 的放宽只对声明方向生效）。
        List<FeatureDefinition> mismatched = List.of(
                FeatureDefinition.raw("clicks", DataType.INT, EntityScope.USER, 1),
                FeatureDefinition.raw("impressions", DataType.INT, EntityScope.USER, 1),
                FeatureDefinition.derived(
                        "bad_ratio", DataType.INT, "div(clicks, impressions)", OutputPolicy.OUTPUT));
        assertThrows(
                DagBuildException.class,
                () -> new LogicalDagBuilder(
                        new ExpressionParser(), OperatorRegistry.standard())
                        .build(mismatched, Set.of("bad_ratio")));
    }

    /** 同类目 ctr/cvr 的输入：USER 域 STRING 维度值序列（365D 窗口与事件投影由上游完成）。 */
    private static FeatureDefinition categorySequence(String name) {
        return FeatureDefinition.builder()
                .name(name)
                .role(FeatureRole.RAW)
                .dataType(DataType.STRING)
                .addEntityScope(EntityScope.USER)
                .sourceBinding(name)
                .declaredValueShape(ValueShape.SEQUENCE)
                .build();
    }

    /** ctr = to_bigint(点击长度 / 曝光长度 × 1000)，分母为 0 由 div 防除 0 返回 0。 */
    private static FeatureDefinition ctrFeature(
            String name, String clickSequence, String impressionSequence, String category) {
        return ratioFeature(name, clickSequence, impressionSequence, category);
    }

    /** cvr = to_bigint(转化长度 / 点击长度 × 1000)，分母为 0 由 div 防除 0 返回 0。 */
    private static FeatureDefinition cvrFeature(
            String name, String conversionSequence, String clickSequence, String category) {
        return ratioFeature(name, conversionSequence, clickSequence, category);
    }

    private static FeatureDefinition ratioFeature(
            String name, String numeratorSequence, String denominatorSequence, String category) {
        return FeatureDefinition.derived(
                name,
                DataType.BIGINT,
                "to_bigint(mul(div("
                        + "get_seq_length(find_indices(" + numeratorSequence + ", '" + category + "')),"
                        + "get_seq_length(find_indices(" + denominatorSequence + ", '" + category + "'))),"
                        + " 1000))",
                OutputPolicy.OUTPUT);
    }

    /** 沿输出特征的 producer 链路按给定端口逐级下钻，返回末端指向的节点 ID。 */
    private static String chainInput(LogicalDag dag, String featureName, int... ports) {
        String nodeId = dag.featureOutput(featureName).producerNodeId();
        for (int port = 0; port < ports.length; port++) {
            nodeId = dag.node(nodeId).inputs().get(ports[port]).nodeId();
        }
        return nodeId;
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
}
