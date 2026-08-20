package com.example.featuredag;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OfflineBatchGenerateRequest;
import com.example.featuredag.api.OfflineBatchGenerateResult;
import com.example.featuredag.api.OfflineGenerateRequest;
import com.example.featuredag.config.FeatureConfig;
import com.example.featuredag.config.FeatureConfigLoader;
import com.example.featuredag.config.FeatureConfigMapper;
import com.example.featuredag.config.FeatureSetConfig;
import com.example.featuredag.config.MappedFeatureSet;
import com.example.featuredag.expression.ExpressionParser;
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
 * 基于现网 transform_test 特征集与真实 HWDSP 365D 点击行的全算子验证。
 *
 * <p>配置在用户原始 4 个 DERIVED 输出（首期 8 算子 + group_count_concat）基础上，
 * 新增 4 个 BASE 原始列与 8 个 DERIVED 输出：每个新增算术/转换算子由独立的新衍生特征
 * 覆盖，操作数混用「BASE 派生标量 × 字面量 × 多路比较」三类形态（如 add(base, 5)），
 * 全部表达式只引用 BASE 原始特征，不引入中间衍生特征。</p>
 *
 * <p>平台输入适配语义：encode=pureDense 的 365D 整型序列按 dft=0 补齐到 365；
 * 核心引擎公共 API 接收的是适配后的 List，因此样例解析器显式模拟该边界转换。
 * dft 仍只在源缺失时生效，筛选未命中静默输出空序列。</p>
 */
public final class TransformTestExtendedOperatorsTest {
    private static final String MODEL_RESOURCE =
            "/model-feature-set-transform-test-extended.json";
    private static final String SAMPLE_RESOURCE =
            "/transform-test-extended-sample.tsv";
    private static final String RESULT_RESOURCE =
            "/model-feature-set-transform-test-extended-result.json";

    private static final String TIMESTAMP = "timestamp";
    private static final String PACKAGE_SEQUENCE =
            "auid_hwdsp_clk_prmt_pkgname_seq_time_365d";
    private static final String SLOT_SEQUENCE =
            "auid_hwdsp_clk_slotid_seq_time_365d";
    private static final String APPC2_SEQUENCE =
            "auid_hwdsp_clk_appc2_seq_time_365d";
    private static final String NET_TYPE_SEQUENCE =
            "auid_hwdsp_clk_net_type_seq_time_365d";
    private static final String HOD_SEQUENCE =
            "auid_hwdsp_clk_hod_seq_time_365d";
    private static final String DOW_SEQUENCE =
            "auid_hwdsp_clk_dow_seq_time_365d";
    private static final String DATE_DIFF_SEQUENCE =
            "auid_hwdsp_clk_date_diff_seq_time_365d";

    // 原 transform_test 的 4 个 DERIVED 输出（表达式原样保留）。
    private static final String PKG_STRENGTH = "small_appc2_click_pkg_strength_seq";
    private static final String PKG_SLOT_AGE = "hwdsp_clk_target_slot_pkg_age_seq_365d";
    private static final String EVENT_CNT_LOG2 = "hwdsp_clk_target_slot_event_cnt_log2_365d";
    private static final String DISTINCT_PKG_LOG2 = "hwdsp_clk_target_slot_distinct_pkg_log2_365d";

    // 新增 8 个 DERIVED 输出：每个对应一个原本缺失的注册算子。
    private static final String PKG_CNT_SMOOTH = "hwdsp_clk_target_slot_pkg_cnt_smooth";
    private static final String PKG_REPEAT_CNT = "hwdsp_clk_pkg_repeat_cnt";
    private static final String PKG_DIVERSITY_PCT = "hwdsp_clk_pkg_diversity_pct";
    private static final String ACTIVE_DAY_RATIO = "hwdsp_clk_active_day_ratio_365d";
    private static final String BEHAVIOR_DIVERSITY_CAPPED = "hwdsp_clk_behavior_diversity_capped";
    private static final String ENV_DIMENSION_MAX = "hwdsp_clk_env_dimension_max";
    private static final String PKG_DIVERSITY_PCT_INT = "hwdsp_clk_pkg_diversity_pct_int";
    private static final String PKG_DAY_COMBO_CNT = "hwdsp_clk_pkg_day_combo_cnt";

    private static final Set<String> RAW_INPUTS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
                    TIMESTAMP, PACKAGE_SEQUENCE, SLOT_SEQUENCE, APPC2_SEQUENCE,
                    NET_TYPE_SEQUENCE, HOD_SEQUENCE, DOW_SEQUENCE, DATE_DIFF_SEQUENCE)));

    private static final List<String> ORDERED_OUTPUTS = Collections.unmodifiableList(
            Arrays.asList(
                    PKG_STRENGTH, PKG_SLOT_AGE, EVENT_CNT_LOG2, DISTINCT_PKG_LOG2,
                    PKG_CNT_SMOOTH, PKG_REPEAT_CNT, PKG_DIVERSITY_PCT, ACTIVE_DAY_RATIO,
                    BEHAVIOR_DIVERSITY_CAPPED, ENV_DIMENSION_MAX,
                    PKG_DIVERSITY_PCT_INT, PKG_DAY_COMBO_CNT));

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

    /** 原始行 81 次点击；pureDense 日期/时段维度由平台补齐到 365。 */
    private static final int TOTAL_CLICKS = 81;
    private static final int PURE_DENSE_LENGTH = 365;
    private static final int DISTINCT_PACKAGES = 47;
    private static final int TARGET_SLOT_CLICKS = 14;
    private static final int DISTINCT_HOURS_WITH_PADDING = 17;
    private static final int DISTINCT_DOWS_WITH_PADDING = 8;
    private static final int DISTINCT_NET_TYPES = 4;

    @Test
    public void dagCoversEntireRegistryWithNewDerivedFeaturesOnly() {
        FeatureSetConfig config = FeatureConfigLoader.load(resource(MODEL_RESOURCE));

        Set<String> baseNames = new LinkedHashSet<String>();
        List<String> derivedNames = new ArrayList<String>();
        for (FeatureConfig feature : config.features()) {
            if ("BASE".equals(feature.definitionType())) {
                baseNames.add(feature.name());
            } else {
                derivedNames.add(feature.name());
            }
        }
        assertEquals(RAW_INPUTS, baseNames);
        assertEquals(ORDERED_OUTPUTS, derivedNames);

        // 无中间特征：新增衍生表达式只允许引用 BASE 原始特征。
        for (FeatureConfig feature : config.features()) {
            if (!"DERIVED".equals(feature.definitionType())) continue;
            for (String derivedName : derivedNames) {
                assertTrue(
                        feature.name() + " must not reference derived feature " + derivedName,
                        !feature.expression().contains(derivedName));
            }
        }

        MappedFeatureSet mapped = FeatureConfigMapper.map(
                config, Collections.<String>emptySet(), Collections.emptyMap());
        assertEquals(ORDERED_OUTPUTS, new ArrayList<String>(mapped.targetFeatures()));

        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), OperatorRegistry.standard())
                .build(mapped.definitions(), mapped.targetFeatures());
        assertEquals(ALL_REGISTERED_OPERATORS, operatorNames(dag));
        assertEquals(RAW_INPUTS, sourceBindings(dag));
    }

    @Test
    public void singleGenerateMatchesGoldenAndHandAnchors() {
        Map<String, List<?>> values = engine().generate(
                new OfflineGenerateRequest("transform-test-extended-single", sampleRow()))
                .featureValues();

        assertEquals(ORDERED_OUTPUTS, new ArrayList<String>(values.keySet()));
        assertFeatureValuesEqual(goldenSection("feature_values"), values);

        // 原 4 输出的行为不受新增特征影响：包名筛选 com.UCMobile 命中 6 次且全部落在实用工具。
        assertEquals(Collections.singletonList("实用工具#6"), values.get(PKG_STRENGTH));
        assertEquals(TARGET_SLOT_CLICKS, values.get(PKG_SLOT_AGE).size());

        // add(base, 5)：目标广告位命中 14 次 + 字面量 5 平滑；双整型载体产出 Long。
        assertEquals(Long.valueOf(TARGET_SLOT_CLICKS + 5L),
                values.get(PKG_CNT_SMOOTH).get(0));

        // sub(base, base)：81 次点击 − 47 个去重包名 = 34 次重复点击。
        assertEquals(Long.valueOf(TOTAL_CLICKS - DISTINCT_PACKAGES),
                values.get(PKG_REPEAT_CNT).get(0));

        // mul(div(base, base), 100)：去重率 47/81 × 100。
        assertEquals(DISTINCT_PACKAGES * 100.0 / TOTAL_CLICKS,
                numberAt(values.get(PKG_DIVERSITY_PCT), 0), 1e-12);

        // pureDense date_diff 补齐到 365：365 / 365 = 1。
        assertEquals(1.0,
                numberAt(values.get(ACTIVE_DAY_RATIO), 0), 1e-12);

        // hod 原值 16 种，补充值 0 新增一个取值：min(47, 17, 50) = 17。
        assertEquals(Integer.valueOf(DISTINCT_HOURS_WITH_PADDING),
                values.get(BEHAVIOR_DIVERSITY_CAPPED).get(0));

        // dow 原值 7 种，补充值 0 新增一个取值：max(4, 8, 5) = 8。
        assertEquals(Integer.valueOf(DISTINCT_DOWS_WITH_PADDING),
                values.get(ENV_DIMENSION_MAX).get(0));

        // to_int 截断：58.02… → 58（Integer 载体）。
        assertEquals(Integer.valueOf(58),
                values.get(PKG_DIVERSITY_PCT_INT).get(0));

        // to_bigint 放大：47 × 365 = 17155（核心载体为 Long；平台可序列化为 17155.0）。
        assertEquals(Long.valueOf(DISTINCT_PACKAGES * PURE_DENSE_LENGTH),
                values.get(PKG_DAY_COMBO_CNT).get(0));
    }

    @Test
    public void batchMatchesSingleAndNoMatchRowKeepsLiteralBaseline() {
        Map<String, List<?>> sample = sampleRow();
        Map<String, List<?>> noMatch = new LinkedHashMap<String, List<?>>(sample);
        noMatch.put(SLOT_SEQUENCE,
                Collections.nCopies(sample.get(SLOT_SEQUENCE).size(), "no_such_slot"));

        FeatureDagEngine engine = engine();
        Map<String, List<?>> single = engine.generate(
                new OfflineGenerateRequest("transform-test-extended-single-for-batch", sample))
                .featureValues();
        OfflineBatchGenerateResult batch = engine.generateBatch(
                new OfflineBatchGenerateRequest(
                        "transform-test-extended-batch", Arrays.asList(sample, noMatch)));

        assertEquals(2, batch.rows().size());
        assertEquals(single, batch.rows().get(0));

        Map<String, List<?>> noMatchValues = batch.rows().get(1);
        assertFeatureValuesEqual(goldenSection("no_match_feature_values"), noMatchValues);
        // 筛选未命中静默输出空序列（dft 不参与，边界语义见 2089bc8）。
        assertTrue(noMatchValues.get(PKG_SLOT_AGE).isEmpty());
        // add(base, 5) 的字面量基线：空集长度 0 + 5 = 5，仍产出 Long 载体。
        assertEquals(Long.valueOf(5L), noMatchValues.get(PKG_CNT_SMOOTH).get(0));
        // 与 slot 筛选无关的特征不受影响。
        assertEquals(Long.valueOf(TOTAL_CLICKS - DISTINCT_PACKAGES),
                noMatchValues.get(PKG_REPEAT_CNT).get(0));
        assertEquals(Long.valueOf(DISTINCT_PACKAGES * PURE_DENSE_LENGTH),
                noMatchValues.get(PKG_DAY_COMBO_CNT).get(0));
    }

    private static FeatureDagEngine engine() {
        return FeatureDagEngine.init(
                resource(MODEL_RESOURCE),
                InitOptions.builder()
                        .environment(ExecutionEnvironment.OFFLINE)
                        .planId("transform-test-extended-ut")
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
            if ("auid".equals(headers[index])) {
                row.put(headers[index], Collections.singletonList(fields[index]));
            } else if (isIntegerSequence(headers[index])) {
                row.put(headers[index], decodeIntegerSequence(
                        headers[index], fields[index]));
            } else {
                row.put(headers[index], Arrays.asList(fields[index].split("\\^", -1)));
            }
        }
        return row;
    }

    /** timestamp/hod/dow/date_diff 四列在数据集定义中均为整型序列。 */
    private static boolean isIntegerSequence(String name) {
        return TIMESTAMP.equals(name)
                || HOD_SEQUENCE.equals(name)
                || DOW_SEQUENCE.equals(name)
                || DATE_DIFF_SEQUENCE.equals(name);
    }

    private static List<Integer> decodeIntegerSequence(String name, String value) {
        List<Integer> result = new ArrayList<Integer>(parseIntegers(value));
        if (isPureDense365d(name)) {
            if (result.size() > PURE_DENSE_LENGTH) {
                throw new AssertionError(
                        "pureDense sequence exceeds 365 elements: " + name);
            }
            while (result.size() < PURE_DENSE_LENGTH) result.add(Integer.valueOf(0));
        }
        return Collections.unmodifiableList(result);
    }

    private static boolean isPureDense365d(String name) {
        return HOD_SEQUENCE.equals(name)
                || DOW_SEQUENCE.equals(name)
                || DATE_DIFF_SEQUENCE.equals(name);
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
     * golden 文件中的数值经 JSON 往返后载体可能与引擎输出不一致，
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
        InputStream stream = TransformTestExtendedOperatorsTest.class.getResourceAsStream(name);
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
