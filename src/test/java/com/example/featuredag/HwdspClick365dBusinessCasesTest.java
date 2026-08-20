package com.example.featuredag;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.FeatureGenerationException;
import com.example.featuredag.api.GenerateResult;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OfflineGenerateRequest;
import com.example.featuredag.config.FeatureConfig;
import com.example.featuredag.config.FeatureConfigLoader;
import com.example.featuredag.config.FeatureConfigMapper;
import com.example.featuredag.config.FeatureSetConfig;
import com.example.featuredag.config.MappedFeatureSet;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.logical.OperatorNode;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 单衍生特征验证：目标 appc2 下的点击推广包名强度序列。 */
public final class HwdspClick365dBusinessCasesTest {
    private static final String MODEL_RESOURCE =
            "/model-feature-set-hwdsp-click-365d.json";
    private static final String SAMPLE_RESOURCE =
            "/model-feature-set-hwdsp-click-365d-sample-input.json";
    private static final String OUTPUT_FEATURE =
            "small_appc2_click_pkg_strength_seq";

    @Test
    public void modelContainsOnlyTwoTableInputsAndOneDerivedFeature() {
        FeatureSetConfig model = FeatureConfigLoader.load(resource(MODEL_RESOURCE));
        Map<String, List<?>> row = sampleRow();

        Set<String> baseBindings = new LinkedHashSet<String>();
        int derivedCount = 0;
        for (FeatureConfig feature : model.features()) {
            if ("BASE".equals(feature.definitionType())) {
                baseBindings.add(feature.rawName());
                assertEquals(List.of("USER"), feature.entityScopes());
            } else {
                derivedCount++;
                assertEquals(OUTPUT_FEATURE, feature.name());
                assertTrue(feature.entityScopes().isEmpty());
            }
        }

        assertEquals(2, baseBindings.size());
        assertEquals(1, derivedCount);
        assertEquals(baseBindings, row.keySet());
        assertEquals(
                row.get("auid_hwdsp_clk_prmt_pkgname_seq_time_365d").size(),
                row.get("auid_hwdsp_clk_appc2_seq_time_365d").size());
    }

    @Test
    public void expressionUsesFindSliceAndGroupCountConcatOnly() {
        FeatureSetConfig config = FeatureConfigLoader.load(resource(MODEL_RESOURCE));
        MappedFeatureSet mapped = FeatureConfigMapper.map(config, Set.of(), Map.of());
        LogicalDag dag = new LogicalDagBuilder(
                new ExpressionParser(), OperatorRegistry.standard())
                .build(mapped.definitions(), mapped.targetFeatures());

        Set<String> operators = new LinkedHashSet<String>();
        dag.nodes().values().stream()
                .filter(OperatorNode.class::isInstance)
                .map(OperatorNode.class::cast)
                .map(OperatorNode::operatorName)
                .forEach(operators::add);

        assertEquals(
                Set.of("find_indices", "slice_by_indices", "group_count_concat"),
                operators);
    }

    @Test
    public void generatesExpectedPackageStrength() {
        GenerateResult result = engine().generate(new OfflineGenerateRequest(
                sampleExecutionId(),
                sampleRow()));

        assertEquals(1, result.featureValues().size());
        // 输出为紧凑元素数组：命中几次就几个元素，无 seq_max_length 补齐。
        assertEquals(List.of("com.UCMobile#3"), result.featureValues().get(OUTPUT_FEATURE));
    }

    @Test
    public void coversNoMatchAndMisalignedSequenceBoundaries() {
        Map<String, List<?>> noMatch = sampleRow();
        noMatch.put(
                "auid_hwdsp_clk_appc2_seq_time_365d",
                List.of("游戏", "游戏", "工具", "资讯", "金融", "阅读"));
        GenerateResult noMatchResult = engine().generate(
                new OfflineGenerateRequest("hwdsp-one-feature-no-match", noMatch));
        // 筛选未命中静默输出空序列：dft 不参与（边界语义见 2089bc8）。
        assertTrue(noMatchResult.featureValues().get(OUTPUT_FEATURE).isEmpty());

        Map<String, List<?>> misaligned = sampleRow();
        misaligned.put(
                "auid_hwdsp_clk_prmt_pkgname_seq_time_365d",
                List.of("com.UCMobile", "com.UCMobile"));
        FeatureGenerationException failure = assertThrows(
                FeatureGenerationException.class,
                () -> engine().generate(new OfflineGenerateRequest(
                        "hwdsp-one-feature-misaligned", misaligned)));
        assertFalse(failure.getMessage().isBlank());
    }

    private static FeatureDagEngine engine() {
        return FeatureDagEngine.init(
                resource(MODEL_RESOURCE),
                InitOptions.builder()
                        .environment(ExecutionEnvironment.OFFLINE)
                        .planId("hwdsp-one-derived-feature-ut")
                        .build());
    }

    private static String sampleExecutionId() {
        return (String) sampleDocument().get("execution_id");
    }

    private static Map<String, List<?>> sampleRow() {
        Object rawValues = sampleDocument().get("row_values");
        if (!(rawValues instanceof Map<?, ?>)) {
            throw new AssertionError("sample row_values must be an object");
        }
        Map<String, List<?>> result = new LinkedHashMap<String, List<?>>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) rawValues).entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof List<?>)) {
                throw new AssertionError("invalid sample row field: " + entry);
            }
            result.put((String) entry.getKey(), (List<?>) entry.getValue());
        }
        return result;
    }

    private static Map<String, Object> sampleDocument() {
        try {
            return new ObjectMapper().readValue(
                    resource(SAMPLE_RESOURCE),
                    new TypeReference<Map<String, Object>>() {});
        } catch (IOException error) {
            throw new AssertionError("Failed to parse sample resource", error);
        }
    }

    private static String resource(String name) {
        InputStream stream = HwdspClick365dBusinessCasesTest.class.getResourceAsStream(name);
        if (stream == null) throw new AssertionError("Missing test resource: " + name);
        try (InputStream input = stream) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new AssertionError("Failed to read test resource: " + name, error);
        }
    }
}
