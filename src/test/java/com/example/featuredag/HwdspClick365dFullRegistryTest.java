package com.example.featuredag;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.GenerateResult;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OfflineBatchGenerateRequest;
import com.example.featuredag.api.OfflineBatchGenerateResult;
import com.example.featuredag.api.OfflineGenerateRequest;
import com.example.featuredag.config.FeatureConfig;
import com.example.featuredag.config.FeatureConfigLoader;
import com.example.featuredag.config.FeatureConfigMapper;
import com.example.featuredag.config.FeatureSetConfig;
import com.example.featuredag.config.MappedFeatureSet;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.logical.LogicalNode;
import com.example.featuredag.logical.OperatorNode;
import com.example.featuredag.logical.SourceNode;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 面向现网特征平台配置格式的注册表全算子验证。
 *
 * <p>特征集配置沿用现网单特征属性全集（encode/tableType/featureCategory 等扩展字段
 * 原样保留），17 个 DERIVED 输出全部只引用 BASE 原始特征（无中间衍生特征），
 * 表达式内联覆盖该历史业务 fixture 的 17 个算子；其中 8 个数值输出专门覆盖
 * BASE/常量/嵌套算术组合，样例行取自真实 HWDSP 365D 点击行。</p>
 */
public final class HwdspClick365dFullRegistryTest {
    private static final String MODEL_RESOURCE =
            "/model-feature-set-hwdsp-click-365d-full-registry.json";
    private static final String VERBATIM_RESOURCE =
            "/model-feature-set-transform-test-verbatim.json";
    private static final String SAMPLE_RESOURCE =
            "/hwdsp-click-365d-full-registry-sample.tsv";
    private static final String RESULT_RESOURCE =
            "/model-feature-set-hwdsp-click-365d-full-registry-result.json";
    private static final String SAMPLES_RESOURCE =
            "/model-feature-set-hwdsp-click-365d-full-registry-samples.json";

    private static final String AUID = "auid";
    private static final String TIMESTAMP = "timestamp";
    private static final String PACKAGE_SEQUENCE =
            "auid_hwdsp_clk_prmt_pkgname_seq_time_365d";
    private static final String SLOT_SEQUENCE =
            "auid_hwdsp_clk_slotid_seq_time_365d";
    private static final String APPC2_SEQUENCE =
            "auid_hwdsp_clk_appc2_seq_time_365d";
    private static final String ORIGINAL_PKG_STRENGTH =
            "small_appc2_click_pkg_strength_seq";
    private static final String TARGET_SLOT_PKG_AGE =
            "hwdsp_clk_target_slot_pkg_age_seq_365d";
    private static final String TARGET_SLOT_EVENT_CNT_LOG2 =
            "hwdsp_clk_target_slot_event_cnt_log2_365d";
    private static final String TARGET_SLOT_DISTINCT_PKG_LOG2 =
            "hwdsp_clk_target_slot_distinct_pkg_log2_365d";
    private static final String SHOPPING_PKG_STRENGTH =
            "shopping_appc2_click_pkg_strength_seq";
    private static final String SHOPPING_PKG_SLOT_AGE =
            "shopping_appc2_click_pkg_slot_age_seq";
    private static final String SHOPPING_PKG_DIVERSITY_LOG2 =
            "shopping_appc2_click_pkg_diversity_log2";
    private static final String CATEGORY_AVG_CNT =
            "appc2_click_category_avg_cnt";
    private static final String CATEGORY_REPEAT_CNT =
            "appc2_click_category_repeat_cnt";
    private static final String AUID_PLUS_5 = "auid_plus_5";
    private static final String AUID_MINUS_5 = "auid_minus_5";
    private static final String AUID_TIMES_3 = "auid_times_3";
    private static final String AUID_DIV_5 = "auid_div_5";
    private static final String AUID_MIN_THRESHOLD = "auid_min_threshold";
    private static final String AUID_MAX_THRESHOLD = "auid_max_threshold";
    private static final String AUID_DIV_5_TO_INT = "auid_div_5_to_int";
    private static final String AUID_PLUS_5_TO_BIGINT = "auid_plus_5_to_bigint";

    private static final Set<String> RAW_INPUTS = setOf(
            AUID, TIMESTAMP, PACKAGE_SEQUENCE, SLOT_SEQUENCE, APPC2_SEQUENCE);
    private static final List<String> ORDERED_OUTPUTS = Collections.unmodifiableList(Arrays.asList(
            ORIGINAL_PKG_STRENGTH,
            TARGET_SLOT_PKG_AGE,
            TARGET_SLOT_EVENT_CNT_LOG2,
            TARGET_SLOT_DISTINCT_PKG_LOG2,
            SHOPPING_PKG_STRENGTH,
            SHOPPING_PKG_SLOT_AGE,
            SHOPPING_PKG_DIVERSITY_LOG2,
            CATEGORY_AVG_CNT,
            CATEGORY_REPEAT_CNT,
            AUID_PLUS_5,
            AUID_MINUS_5,
            AUID_TIMES_3,
            AUID_DIV_5,
            AUID_MIN_THRESHOLD,
            AUID_MAX_THRESHOLD,
            AUID_DIV_5_TO_INT,
            AUID_PLUS_5_TO_BIGINT));
    private static final Set<String> FINAL_OUTPUTS =
            Collections.unmodifiableSet(new LinkedHashSet<>(ORDERED_OUTPUTS));

    /** 注册表当前全部标准算子；注册表增删算子时本清单须同步维护。 */
    private static final Set<String> ALL_REGISTERED_OPERATORS = setOf(
            "discrete",
            "log_base",
            "slice_by_indices",
            "find_indices",
            "get_seq_length",
            "count_distinct",
            "zip_concat",
            "group_count_concat",
            "calc_delta_seq",
            "to_int",
            "to_bigint",
            "min",
            "max",
            "add",
            "sub",
            "mul",
            "div");

    @Test
    public void verbatimPlatformConfigParsesBuildsAndGenerates() {
        // 用户提供的原样配置：含 encode/tableType/featureCategory/is_train_feature 等平台扩展
        // 字段、字符串型布尔（is_feedback:"true"）与 table_configs 顶层对象，必须无损通过解析。
        FeatureSetConfig config = FeatureConfigLoader.load(resource(VERBATIM_RESOURCE));
        MappedFeatureSet mapped = FeatureConfigMapper.map(
                config, Collections.<String>emptySet(), Collections.emptyMap());

        assertEquals(
                Collections.singleton("small_appc2_click_pkg_strength_seq"),
                mapped.targetFeatures());

        LogicalDag dag = new LogicalDagBuilder(
                new com.example.featuredag.expression.ExpressionParser(),
                OperatorRegistry.standard())
                .build(mapped.definitions(), mapped.targetFeatures());
        assertEquals(
                setOf("find_indices", "slice_by_indices", "group_count_concat"),
                operatorNames(dag));

        FeatureDagEngine engine = FeatureDagEngine.init(
                resource(VERBATIM_RESOURCE),
                InitOptions.builder()
                        .environment(ExecutionEnvironment.OFFLINE)
                        .planId("transform-test-verbatim-ut")
                        .build());
        Map<String, List<?>> values = engine.generate(new OfflineGenerateRequest(
                "transform-test-verbatim-single",
                subRow(sampleRow(), PACKAGE_SEQUENCE, APPC2_SEQUENCE))).featureValues();

        // 样例行 appc2 序列中没有目标类目取值：筛选未命中静默输出空序列，dft 不参与
        // （边界语义见 2089bc8：dft 仅在源缺失时生效）。
        assertEquals(
                Collections.singleton("small_appc2_click_pkg_strength_seq"),
                values.keySet());
        assertEquals(
                Collections.emptyList(),
                values.get("small_appc2_click_pkg_strength_seq"));
    }

    @Test
    public void productionExtensionFieldsSurviveLoading() {
        FeatureSetConfig config = FeatureConfigLoader.load(resource(MODEL_RESOURCE));

        assertTrue(config.additionalProperties().containsKey("table_configs"));
        for (FeatureConfig feature : config.features()) {
            assertTrue(
                    feature.name() + " must keep platform field tableType",
                    feature.additionalProperties().containsKey("tableType"));
            assertTrue(
                    feature.name() + " must keep platform field encode",
                    feature.additionalProperties().containsKey("encode"));
        }
    }

    @Test
    public void dagCoversEntireRegistryWithoutIntermediateFeatures() {
        FeatureSetConfig config = FeatureConfigLoader.load(resource(MODEL_RESOURCE));

        Set<String> baseNames = new LinkedHashSet<String>();
        Set<String> derivedNames = new LinkedHashSet<String>();
        List<FeatureConfig> derivedConfigs = new ArrayList<FeatureConfig>();
        for (FeatureConfig feature : config.features()) {
            if ("BASE".equals(feature.definitionType())) {
                baseNames.add(feature.name());
                assertEquals("OUTPUT", feature.outputPolicy());
            } else {
                derivedNames.add(feature.name());
                derivedConfigs.add(feature);
                assertEquals("OUTPUT", feature.outputPolicy());
            }
        }
        assertEquals(RAW_INPUTS, baseNames);
        assertEquals(FINAL_OUTPUTS, derivedNames);

        // 无中间特征：每个 DERIVED 表达式只允许引用 BASE 原始特征。
        for (FeatureConfig feature : derivedConfigs) {
            for (String derivedName : derivedNames) {
                assertTrue(
                        feature.name() + " must not reference derived feature " + derivedName,
                        !feature.expression().contains(derivedName));
            }
        }

        MappedFeatureSet mapped = FeatureConfigMapper.map(
                config, Collections.<String>emptySet(), Collections.emptyMap());
        assertEquals(FINAL_OUTPUTS, mapped.targetFeatures());
        // order 属性驱动输出顺序：1..17 依次对应原模型、扩展业务和算术测试输出。
        List<String> outputOrder = new ArrayList<String>();
        for (var output : mapped.outputs()) outputOrder.add(output.featureName());
        assertEquals(ORDERED_OUTPUTS, outputOrder);

        LogicalDag dag = new LogicalDagBuilder(
                new com.example.featuredag.expression.ExpressionParser(),
                OperatorRegistry.standard())
                .build(mapped.definitions(), mapped.targetFeatures());

        Set<String> operators = operatorNames(dag);
        // fixture 清单中的每个算子都必须被最终输出表达式触达，且 DAG 中没有清单外算子。
        assertEquals(ALL_REGISTERED_OPERATORS, operators);
        assertEquals(RAW_INPUTS, sourceBindings(dag));

        OperatorRegistry registry = OperatorRegistry.standard();
        for (String operator : ALL_REGISTERED_OPERATORS) {
            assertTrue(
                    operator + " must be registered",
                    registry.find(operator).isPresent());
        }
    }

    @Test
    public void singleGenerateMatchesGoldenResult() {
        GenerateResult result = engine().generate(new OfflineGenerateRequest(
                "hwdsp-click-365d-full-registry-single", sampleRow()));
        Map<String, List<?>> values = result.featureValues();

        assertEquals(FINAL_OUTPUTS, values.keySet());
        assertFeatureValuesEqual(
                goldenSection("feature_values"), values);

        // 手工锚点：目标类目 6 次点击（idlefish×1、taobao×4、jingyangou×1），按首现顺序分组计数。
        assertEquals(
                Arrays.asList(
                        "com.taobao.idlefish#1",
                        "com.taobao.taobao#4",
                        "com.whyixiu.jingyangou#1"),
                values.get(SHOPPING_PKG_STRENGTH));
        assertEquals(6, values.get(SHOPPING_PKG_SLOT_AGE).size());
        assertEquals(
                "com.taobao.idlefish|k4hs367teq|44.482592592592596",
                values.get(SHOPPING_PKG_SLOT_AGE).get(0));
        // 去重率 3/6→51 分桶 3，log2(3)；to_int 产 Integer，to_bigint 产 Long。
        assertEquals(
                Math.log(3.0) / Math.log(2.0),
                numberAt(values.get(SHOPPING_PKG_DIVERSITY_LOG2), 0), 1e-12);
        assertEquals(Integer.valueOf(5), values.get(CATEGORY_AVG_CNT).get(0));
        assertEquals(Long.valueOf(65L), values.get(CATEGORY_REPEAT_CNT).get(0));

        // 用户原模型四个输出保持不变：UCMobile 共 6 次，目标 slot 共 14 次/10 个包名。
        assertEquals(
                Collections.singletonList("实用工具#6"),
                values.get(ORIGINAL_PKG_STRENGTH));
        assertEquals(14, values.get(TARGET_SLOT_PKG_AGE).size());
        assertEquals(
                "com.UCMobile|k4hs367teq|3.0712384259259258",
                values.get(TARGET_SLOT_PKG_AGE).get(0));
        assertEquals(
                Math.log(5.0) / Math.log(2.0),
                numberAt(values.get(TARGET_SLOT_EVENT_CNT_LOG2), 0), 1e-12);
        assertEquals(
                Math.log(5.0) / Math.log(2.0),
                numberAt(values.get(TARGET_SLOT_DISTINCT_PKG_LOG2), 0), 1e-12);

        // 用户样例 auid=1234：直接覆盖 BASE/常量、可变参数以及嵌套转换类别。
        assertEquals(Long.valueOf(1239L), values.get(AUID_PLUS_5).get(0));
        assertEquals(Long.valueOf(1229L), values.get(AUID_MINUS_5).get(0));
        assertEquals(Long.valueOf(3702L), values.get(AUID_TIMES_3).get(0));
        assertEquals(246.8, numberAt(values.get(AUID_DIV_5), 0), 1e-12);
        assertEquals(Integer.valueOf(1000), values.get(AUID_MIN_THRESHOLD).get(0));
        assertEquals(Integer.valueOf(2000), values.get(AUID_MAX_THRESHOLD).get(0));
        assertEquals(Integer.valueOf(246), values.get(AUID_DIV_5_TO_INT).get(0));
        assertEquals(Long.valueOf(1239L), values.get(AUID_PLUS_5_TO_BIGINT).get(0));
    }

    @Test
    public void arithmeticFeaturesCoverBaseLiteralVariadicAndNestedCalls() {
        FeatureSetConfig config = FeatureConfigLoader.load(resource(MODEL_RESOURCE));
        Map<String, String> expressions = new LinkedHashMap<String, String>();
        for (FeatureConfig feature : config.features()) {
            if ("DERIVED".equals(feature.definitionType())) {
                expressions.put(feature.name(), feature.expression());
            }
        }

        assertEquals("add(auid, 5)", expressions.get(AUID_PLUS_5));
        assertEquals("sub(auid, 5)", expressions.get(AUID_MINUS_5));
        assertEquals("mul(auid, 3)", expressions.get(AUID_TIMES_3));
        assertEquals("div(auid, 5)", expressions.get(AUID_DIV_5));
        assertEquals("min(auid, 1000, 2000)", expressions.get(AUID_MIN_THRESHOLD));
        assertEquals("max(auid, 1000, 2000)", expressions.get(AUID_MAX_THRESHOLD));
        assertEquals("to_int(div(auid, 5))", expressions.get(AUID_DIV_5_TO_INT));
        assertEquals("to_bigint(add(auid, 5))", expressions.get(AUID_PLUS_5_TO_BIGINT));
    }

    @Test
    public void offlineBatchMatchesSingleAndHandlesNoTargetCategory() {
        Map<String, List<?>> sample = sampleRow();
        Map<String, List<?>> noMatch = new LinkedHashMap<String, List<?>>(sample);
        noMatch.put(
                APPC2_SEQUENCE,
                Collections.nCopies(sample.get(APPC2_SEQUENCE).size(), "no_such_category"));

        FeatureDagEngine engine = engine();
        Map<String, List<?>> single = engine.generate(new OfflineGenerateRequest(
                "hwdsp-click-365d-full-registry-single-for-batch", sample)).featureValues();
        OfflineBatchGenerateResult batch = engine.generateBatch(
                new OfflineBatchGenerateRequest(
                        "hwdsp-click-365d-full-registry-batch",
                        Arrays.asList(sample, noMatch)));

        assertEquals(2, batch.rows().size());
        assertEquals(single, batch.rows().get(0));
        assertFeatureValuesEqual(
                goldenSection("no_match_feature_values"), batch.rows().get(1));
    }

    @Test
    public void documentedSampleRowsMatchEngineOutputs() {
        // 样例文件自文档化：rows[].row_values 为输入行，expected_feature_values 为引擎应产出值，
        // 三条样例均派生自同一条真实点击行（整行 / 前 20 次点击前缀 / 目标类目替换为零命中）。
        Map<String, Object> document;
        try {
            document = new ObjectMapper().readValue(
                    resource(SAMPLES_RESOURCE),
                    new TypeReference<Map<String, Object>>() {});
        } catch (IOException error) {
            throw new AssertionError("Failed to parse samples resource", error);
        }
        Object rawRows = document.get("rows");
        if (!(rawRows instanceof List<?>) || ((List<?>) rawRows).isEmpty()) {
            throw new AssertionError("Samples resource must contain a non-empty rows array");
        }
        List<Map<String, List<?>>> inputs = new ArrayList<Map<String, List<?>>>();
        List<Map<String, List<?>>> expectedOutputs = new ArrayList<Map<String, List<?>>>();
        List<String> names = new ArrayList<String>();
        for (Object rawRow : (List<?>) rawRows) {
            if (!(rawRow instanceof Map<?, ?>)) {
                throw new AssertionError("Each sample row must be an object");
            }
            Map<?, ?> row = (Map<?, ?>) rawRow;
            names.add(String.valueOf(row.get("sample_name")));
            inputs.add(stringListMap(row.get("row_values"), "row_values"));
            expectedOutputs.add(
                    stringListMap(row.get("expected_feature_values"), "expected_feature_values"));
        }

        OfflineBatchGenerateResult batch = engine().generateBatch(
                new OfflineBatchGenerateRequest(
                        "hwdsp-click-365d-full-registry-samples", inputs));

        assertEquals(names.size(), batch.rows().size());
        for (int index = 0; index < names.size(); index++) {
            assertEquals(FINAL_OUTPUTS, batch.rows().get(index).keySet());
            assertFeatureValuesEqual(
                    expectedOutputs.get(index), batch.rows().get(index));
        }
    }

    private static FeatureDagEngine engine() {
        return FeatureDagEngine.init(
                resource(MODEL_RESOURCE),
                InitOptions.builder()
                        .environment(ExecutionEnvironment.OFFLINE)
                        .planId("hwdsp-click-365d-full-registry-ut")
                        .build());
    }

    private static Set<String> operatorNames(LogicalDag dag) {
        Set<String> operators = new LinkedHashSet<String>();
        for (LogicalNode node : dag.nodes().values()) {
            if (node instanceof OperatorNode) {
                operators.add(((OperatorNode) node).operatorName());
            }
        }
        return operators;
    }

    private static Set<String> sourceBindings(LogicalDag dag) {
        Set<String> sources = new LinkedHashSet<String>();
        for (LogicalNode node : dag.nodes().values()) {
            if (node instanceof SourceNode) {
                sources.add(((SourceNode) node).sourceBinding());
            }
        }
        return sources;
    }

    private static Map<String, List<?>> sampleRow() {
        String[] lines = resource(SAMPLE_RESOURCE).strip().split("\\R");
        if (lines.length != 2) {
            throw new AssertionError("Expected one TSV header and one data row");
        }
        String[] headers = lines[0].split("\\t", -1);
        String[] fields = lines[1].split("\\t", -1);
        if (headers.length != fields.length) {
            throw new AssertionError(
                    "TSV header/data column mismatch: " + headers.length + "/" + fields.length);
        }

        Map<String, List<?>> row = new LinkedHashMap<String, List<?>>();
        for (int index = 0; index < headers.length; index++) {
            if (AUID.equals(headers[index])) {
                row.put(headers[index], Collections.singletonList(Integer.valueOf(fields[index])));
            } else if (TIMESTAMP.equals(headers[index])) {
                row.put(headers[index], parseIntegers(fields[index]));
            } else {
                row.put(headers[index], Arrays.asList(fields[index].split("\\^", -1)));
            }
        }
        return row;
    }

    private static Map<String, List<?>> subRow(Map<String, List<?>> row, String... keys) {
        Map<String, List<?>> result = new LinkedHashMap<String, List<?>>();
        for (String key : keys) result.put(key, row.get(key));
        return result;
    }

    private static Map<String, List<?>> stringListMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?>)) {
            throw new AssertionError("Samples " + field + " must be an object");
        }
        Map<String, List<?>> result = new LinkedHashMap<String, List<?>>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
            if (!(entry.getKey() instanceof String)
                    || !(entry.getValue() instanceof List<?>)) {
                throw new AssertionError("Invalid samples " + field + " entry: " + entry);
            }
            result.put((String) entry.getKey(), (List<?>) entry.getValue());
        }
        return result;
    }

    private static Map<String, List<?>> goldenSection(String section) {
        Map<String, Object> document;
        try {
            document = new ObjectMapper().readValue(
                    resource(RESULT_RESOURCE),
                    new TypeReference<Map<String, Object>>() {});
        } catch (IOException error) {
            throw new AssertionError("Failed to parse golden result resource", error);
        }
        Object sectionValues = document.get(section);
        if (!(sectionValues instanceof Map<?, ?>)) {
            throw new AssertionError("Golden result section must be an object: " + section);
        }
        Map<String, List<?>> result = new LinkedHashMap<String, List<?>>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) sectionValues).entrySet()) {
            if (!(entry.getKey() instanceof String)
                    || !(entry.getValue() instanceof List<?>)) {
                throw new AssertionError("Invalid golden feature value: " + entry);
            }
            result.put((String) entry.getKey(), (List<?>) entry.getValue());
        }
        return result;
    }

    /**
     * golden 文件中的数值经 JSON 往返后 Integer/Long/Double 载体可能与引擎输出不一致，
     * 因此字符串按相等比较、数值按 double 逐位比较，载体类型由专项断言覆盖。
     */
    private static void assertFeatureValuesEqual(
            Map<String, List<?>> expected, Map<String, List<?>> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (Map.Entry<String, List<?>> entry : expected.entrySet()) {
            List<?> expectedValues = entry.getValue();
            List<?> actualValues = actual.get(entry.getKey());
            assertEquals(
                    entry.getKey() + " length mismatch",
                    expectedValues.size(), actualValues.size());
            for (int index = 0; index < expectedValues.size(); index++) {
                Object expectedValue = expectedValues.get(index);
                Object actualValue = actualValues.get(index);
                if (expectedValue instanceof Number && actualValue instanceof Number) {
                    assertEquals(
                            entry.getKey() + "[" + index + "] mismatch",
                            ((Number) expectedValue).doubleValue(),
                            ((Number) actualValue).doubleValue(), 1e-12);
                } else {
                    assertEquals(entry.getKey() + "[" + index + "] mismatch",
                            expectedValue, actualValue);
                }
            }
        }
    }

    private static List<Integer> parseIntegers(String value) {
        String[] elements = value.split("\\^", -1);
        List<Integer> result = new ArrayList<Integer>(elements.length);
        for (String element : elements) {
            result.add(Integer.valueOf(element));
        }
        return Collections.unmodifiableList(result);
    }

    private static double numberAt(List<?> values, int index) {
        return ((Number) values.get(index)).doubleValue();
    }

    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(
                new LinkedHashSet<String>(Arrays.asList(values)));
    }

    private static String resource(String name) {
        InputStream stream = HwdspClick365dFullRegistryTest.class.getResourceAsStream(name);
        if (stream == null) {
            throw new AssertionError("Missing test resource: " + name);
        }
        try (InputStream input = stream) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new AssertionError("Failed to read test resource: " + name, error);
        }
    }
}
