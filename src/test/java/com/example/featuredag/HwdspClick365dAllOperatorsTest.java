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
import com.example.featuredag.expression.AstCall;
import com.example.featuredag.expression.AstNode;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.logical.LogicalNode;
import com.example.featuredag.logical.OperatorNode;
import com.example.featuredag.logical.SourceNode;
import com.example.featuredag.operator.BatchKernelKind;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 基于真实 HWDSP 365D 点击行的首期 8 算子模型用例。
 *
 * <p>模型只声明三个原始输入和三个最终输出；最终表达式直接内联所有计算，
 * 不暴露筛选下标、切片、时间差、计数或分桶等中间衍生特征。</p>
 */
public final class HwdspClick365dAllOperatorsTest {
    private static final String MODEL_RESOURCE =
            "/model-feature-set-hwdsp-click-365d-all-operators.json";
    private static final String SAMPLE_RESOURCE =
            "/hwdsp-click-365d-all-operators-sample.tsv";
    private static final String RESULT_RESOURCE =
            "/model-feature-set-hwdsp-click-365d-all-operators-result.json";
    private static final String TIMESTAMP = "timestamp";
    private static final String PACKAGE_SEQUENCE =
            "auid_hwdsp_clk_prmt_pkgname_seq_time_365d";
    private static final String SLOT_SEQUENCE =
            "auid_hwdsp_clk_slotid_seq_time_365d";
    private static final String ZIPPED_EVENTS =
            "hwdsp_clk_target_slot_pkg_age_seq_365d";
    private static final String DISTINCT_PACKAGE_LOG2 =
            "hwdsp_clk_target_slot_distinct_pkg_log2_365d";
    private static final String EVENT_COUNT_LOG2 =
            "hwdsp_clk_target_slot_event_cnt_log2_365d";
    private static final Set<String> RAW_INPUTS =
            setOf(TIMESTAMP, PACKAGE_SEQUENCE, SLOT_SEQUENCE);
    private static final Set<String> FINAL_OUTPUTS =
            setOf(ZIPPED_EVENTS, DISTINCT_PACKAGE_LOG2, EVENT_COUNT_LOG2);
    private static final Set<String> INITIAL_OPERATORS = setOf(
            "discrete",
            "log_base",
            "slice_by_indices",
            "find_indices",
            "get_seq_length",
            "count_distinct",
            "zip_concat",
            "calc_delta_seq");

    @Test
    public void rawTsvKeepsTheProvidedAlignedSequenceBoundary() {
        Map<String, List<?>> row = sampleRow();

        assertEquals(Collections.singletonList("123456"), row.get("auid"));
        assertEquals(81, row.get(TIMESTAMP).size());
        assertEquals(81, row.get(PACKAGE_SEQUENCE).size());
        assertEquals(81, row.get(SLOT_SEQUENCE).size());
        assertEquals(row.get(TIMESTAMP).size(), row.get(PACKAGE_SEQUENCE).size());
        assertEquals(row.get(TIMESTAMP).size(), row.get(SLOT_SEQUENCE).size());
        assertEquals("missing", row.get(PACKAGE_SEQUENCE).get(80));
        assertEquals("w3tu2puzip", row.get(SLOT_SEQUENCE).get(80));
    }

    @Test
    public void modelContainsOnlyRawInputsAndFinalDeepOutputs() {
        FeatureSetConfig config = FeatureConfigLoader.load(resource(MODEL_RESOURCE));
        Set<String> rawInputs = new LinkedHashSet<String>();
        Set<String> derivedOutputs = new LinkedHashSet<String>();
        List<FeatureConfig> derivedConfigs = new ArrayList<FeatureConfig>();

        for (FeatureConfig feature : config.features()) {
            if ("BASE".equals(feature.definitionType())) {
                rawInputs.add(feature.rawName());
            } else {
                derivedOutputs.add(feature.name());
                derivedConfigs.add(feature);
                assertEquals("OUTPUT", feature.outputPolicy());
                assertFalse("Derived feature must not use the Base auid_ prefix: "
                        + feature.name(), feature.name().startsWith("auid_"));
            }
        }

        assertEquals(RAW_INPUTS, rawInputs);
        assertEquals(FINAL_OUTPUTS, derivedOutputs);
        assertEquals(3, derivedConfigs.size());

        int deepestExpression = 0;
        ExpressionParser parser = new ExpressionParser();
        for (FeatureConfig feature : derivedConfigs) {
            for (String derivedName : derivedOutputs) {
                assertFalse(
                        feature.name() + " must not reference derived output " + derivedName,
                        feature.expression().contains(derivedName));
            }
            deepestExpression = Math.max(
                    deepestExpression,
                    callDepth(parser.parse(feature.expression())));
        }
        assertTrue("Expected an expression with at least seven nested operator calls",
                deepestExpression >= 7);

        MappedFeatureSet mapped = FeatureConfigMapper.map(
                config,
                Collections.<String>emptySet(),
                Collections.emptyMap());
        assertEquals(FINAL_OUTPUTS, mapped.targetFeatures());
    }

    @Test
    public void reachableDagUsesAllEightInitialOperatorsAndRealBindings() {
        FeatureSetConfig config = FeatureConfigLoader.load(resource(MODEL_RESOURCE));
        MappedFeatureSet mapped = FeatureConfigMapper.map(
                config,
                Collections.<String>emptySet(),
                Collections.emptyMap());
        LogicalDag dag = new LogicalDagBuilder(
                new ExpressionParser(), OperatorRegistry.standard())
                .build(mapped.definitions(), mapped.targetFeatures());

        Set<String> operators = new LinkedHashSet<String>();
        Set<String> sources = new LinkedHashSet<String>();
        for (LogicalNode node : dag.nodes().values()) {
            if (node instanceof OperatorNode) {
                operators.add(((OperatorNode) node).operatorName());
            } else if (node instanceof SourceNode) {
                sources.add(((SourceNode) node).sourceBinding());
            }
        }

        assertEquals(INITIAL_OPERATORS, operators);
        assertEquals(RAW_INPUTS, sources);

        OperatorRegistry registry = OperatorRegistry.standard();
        assertEquals(BatchKernelKind.NATIVE, registry.batchKernelKind("find_indices"));
        assertEquals(BatchKernelKind.NATIVE, registry.batchKernelKind("count_distinct"));
        assertEquals(BatchKernelKind.NATIVE, registry.batchKernelKind("zip_concat"));
        assertEquals(BatchKernelKind.NATIVE, registry.batchKernelKind("calc_delta_seq"));
        assertEquals(BatchKernelKind.SCALAR_ADAPTER, registry.batchKernelKind("discrete"));
        assertEquals(BatchKernelKind.SCALAR_ADAPTER, registry.batchKernelKind("log_base"));
        assertEquals(BatchKernelKind.SCALAR_ADAPTER, registry.batchKernelKind("slice_by_indices"));
        assertEquals(BatchKernelKind.SCALAR_ADAPTER, registry.batchKernelKind("get_seq_length"));
    }

    @Test
    public void generatedValuesExactlyMatchThePublishedResultSet() {
        GenerateResult result = engine().generate(new OfflineGenerateRequest(
                "hwdsp-click-365d-all-operators-single",
                sampleRow()));
        Map<String, List<?>> values = result.featureValues();

        assertEquals(FINAL_OUTPUTS, values.keySet());
        Map<String, List<?>> expected = expectedFeatureValues();
        // 输出为紧凑元素数组：筛选命中 14 次即 14 个元素，无 seq_max_length 补齐。
        assertEquals(expected.get(ZIPPED_EVENTS), values.get(ZIPPED_EVENTS));
        assertFeature(values, DISTINCT_PACKAGE_LOG2, expected.get(DISTINCT_PACKAGE_LOG2));
        assertFeature(values, EVENT_COUNT_LOG2, expected.get(EVENT_COUNT_LOG2));

        List<?> zipped = values.get(ZIPPED_EVENTS);
        assertEquals(14, zipped.size());
        assertEquals(
                "com.UCMobile|k4hs367teq|3.0712384259259258",
                zipped.get(0));
        assertEquals(
                "com.zhijun.bookstore|k4hs367teq|58.36547453703704",
                zipped.get(13));
        assertEquals(
                Math.log(5.0) / Math.log(2.0),
                numberAt(values.get(DISTINCT_PACKAGE_LOG2), 0),
                1e-12);
        assertEquals(
                Math.log(5.0) / Math.log(2.0),
                numberAt(values.get(EVENT_COUNT_LOG2), 0),
                1e-12);
    }

    @Test
    public void offlineBatchMatchesSingleAndCoversNoTargetSlot() {
        Map<String, List<?>> sample = sampleRow();
        Map<String, List<?>> noMatch = new LinkedHashMap<String, List<?>>(sample);
        noMatch.put(
                SLOT_SEQUENCE,
                Collections.nCopies(sample.get(SLOT_SEQUENCE).size(), "no_such_slot"));

        FeatureDagEngine engine = engine();
        Map<String, List<?>> single = engine.generate(new OfflineGenerateRequest(
                "hwdsp-click-365d-all-operators-single-for-batch",
                sample)).featureValues();
        OfflineBatchGenerateResult batch = engine.generateBatch(
                new OfflineBatchGenerateRequest(
                        "hwdsp-click-365d-all-operators-batch",
                        Arrays.asList(sample, noMatch)));

        assertEquals(2, batch.rows().size());
        assertEquals(single, batch.rows().get(0));

        Map<String, List<?>> empty = batch.rows().get(1);
        assertEquals(FINAL_OUTPUTS, empty.keySet());
        // 筛选未命中静默输出空序列：dft 不参与（边界语义见 2089bc8）。
        assertTrue(empty.get(ZIPPED_EVENTS).isEmpty());
        assertFeature(empty, DISTINCT_PACKAGE_LOG2, Collections.singletonList(1.0));
        assertFeature(empty, EVENT_COUNT_LOG2, Collections.singletonList(1.0));
    }

    private static FeatureDagEngine engine() {
        return FeatureDagEngine.init(
                resource(MODEL_RESOURCE),
                InitOptions.builder()
                        .environment(ExecutionEnvironment.OFFLINE)
                        .planId("hwdsp-click-365d-no-intermediate-all-operators-ut")
                        .build());
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
            } else if (TIMESTAMP.equals(headers[index])) {
                row.put(headers[index], parseIntegers(fields[index]));
            } else {
                row.put(headers[index], Arrays.asList(fields[index].split("\\^", -1)));
            }
        }
        return row;
    }

    private static Map<String, List<?>> expectedFeatureValues() {
        Map<String, Object> document;
        try {
            document = new ObjectMapper().readValue(
                    resource(RESULT_RESOURCE),
                    new TypeReference<Map<String, Object>>() {});
        } catch (IOException error) {
            throw new AssertionError("Failed to parse expected result resource", error);
        }
        Object rawValues = document.get("feature_values");
        if (!(rawValues instanceof Map<?, ?>)) {
            throw new AssertionError("Expected result feature_values must be an object");
        }
        Map<String, List<?>> result = new LinkedHashMap<String, List<?>>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) rawValues).entrySet()) {
            if (!(entry.getKey() instanceof String)
                    || !(entry.getValue() instanceof List<?>)) {
                throw new AssertionError("Invalid expected feature value: " + entry);
            }
            result.put((String) entry.getKey(), (List<?>) entry.getValue());
        }
        return result;
    }

    private static int callDepth(AstNode node) {
        if (!(node instanceof AstCall)) {
            return 0;
        }
        int childDepth = 0;
        for (AstNode argument : ((AstCall) node).arguments()) {
            childDepth = Math.max(childDepth, callDepth(argument));
        }
        return childDepth + 1;
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

    private static void assertFeature(
            Map<String, List<?>> values,
            String name,
            List<?> expected) {
        assertEquals(name, expected, values.get(name));
    }

    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(
                new LinkedHashSet<String>(Arrays.asList(values)));
    }

    private static String resource(String name) {
        InputStream stream = HwdspClick365dAllOperatorsTest.class.getResourceAsStream(name);
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
