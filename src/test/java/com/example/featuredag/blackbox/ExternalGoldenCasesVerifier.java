package com.example.featuredag.blackbox;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.GenerateResult;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OfflineBatchGenerateRequest;
import com.example.featuredag.api.OfflineBatchGenerateResult;
import com.example.featuredag.api.OfflineGenerateRequest;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 从公共 API 边界读取黄金数据，同时验证逐条调用和离线批调用。
 *
 * <p>该校验器不读取 DAG、物理计划或算子内部状态，确保夹具可被外部调用方复用。
 */
public final class ExternalGoldenCasesVerifier {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ExternalGoldenCasesVerifier() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: ExternalGoldenCasesVerifier <feature-set.json> <cases.json>");
        }
        Path featureSetPath = Path.of(args[0]);
        Path casesPath = Path.of(args[1]);
        String featureSetJson = Files.readString(featureSetPath, StandardCharsets.UTF_8);
        Map<String, Object> suite = OBJECT_MAPPER.readValue(
                Files.readString(casesPath, StandardCharsets.UTF_8),
                new TypeReference<Map<String, Object>>() {});

        requireOfflineEnvironment(suite);
        double tolerance = numericTolerance(suite);
        Set<String> targetFeatures = new LinkedHashSet<String>(
                stringList(suite.get("target_features"), "target_features"));
        FeatureDagEngine engine = FeatureDagEngine.init(
                featureSetJson,
                InitOptions.builder()
                        .environment(ExecutionEnvironment.OFFLINE)
                        .planId("external-golden-cases-verifier")
                        .targetFeatures(targetFeatures)
                        .build());

        List<Map<String, Object>> cases = objectList(suite.get("cases"), "cases");
        List<Map<String, List<?>>> batchRows = new ArrayList<Map<String, List<?>>>(cases.size());
        List<Map<String, List<?>>> expectedBatchRows =
                new ArrayList<Map<String, List<?>>>(cases.size());
        for (Map<String, Object> goldenCase : cases) {
            String caseId = stringValue(goldenCase.get("case_id"), "case_id");
            Map<String, Object> input = objectValue(goldenCase.get("input"), caseId + ".input");
            String executionId = stringValue(
                    input.get("execution_id"), caseId + ".input.execution_id");
            Map<String, List<?>> rowValues = featureMap(
                    input.get("row_values"), caseId + ".input.row_values");
            Map<String, Object> expected = objectValue(
                    goldenCase.get("expected_output"), caseId + ".expected_output");
            String expectedExecutionId = stringValue(
                    expected.get("execution_id"), caseId + ".expected_output.execution_id");
            Map<String, List<?>> expectedValues = featureMap(
                    expected.get("feature_values"),
                    caseId + ".expected_output.feature_values");

            GenerateResult actual = engine.generate(
                    new OfflineGenerateRequest(executionId, rowValues));
            requireEqual(expectedExecutionId, actual.executionId(), caseId + ".execution_id");
            compareFeatureMaps(expectedValues, actual.featureValues(), tolerance, caseId);
            batchRows.add(rowValues);
            expectedBatchRows.add(expectedValues);
        }

        OfflineBatchGenerateResult batchResult = engine.generateBatch(
                new OfflineBatchGenerateRequest("external-golden-batch", batchRows));
        requireEqual("external-golden-batch", batchResult.executionId(), "batch.execution_id");
        requireEqual(expectedBatchRows.size(), batchResult.rows().size(), "batch.row_count");
        for (int index = 0; index < expectedBatchRows.size(); index++) {
            compareFeatureMaps(
                    expectedBatchRows.get(index),
                    batchResult.rows().get(index),
                    tolerance,
                    "batch.rows[" + index + "]");
        }
        System.out.println(
                "External golden cases passed: " + cases.size()
                        + " single requests and 1 batch request.");
    }

    private static void requireOfflineEnvironment(Map<String, Object> suite) {
        String environment = stringValue(suite.get("environment"), "environment");
        if (!"OFFLINE".equals(environment)) {
            throw new IllegalArgumentException(
                    "This verifier expects environment OFFLINE, got: " + environment);
        }
    }

    private static double numericTolerance(Map<String, Object> suite) {
        Map<String, Object> comparison = objectValue(suite.get("comparison"), "comparison");
        Object value = comparison.get("numeric_absolute_tolerance");
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(
                    "comparison.numeric_absolute_tolerance must be numeric");
        }
        double tolerance = ((Number) value).doubleValue();
        if (!Double.isFinite(tolerance) || tolerance < 0.0) {
            throw new IllegalArgumentException(
                    "comparison.numeric_absolute_tolerance must be finite and non-negative");
        }
        return tolerance;
    }

    private static void compareFeatureMaps(
            Map<String, List<?>> expected,
            Map<String, List<?>> actual,
            double tolerance,
            String path) {
        requireEqual(expected.keySet(), actual.keySet(), path + ".feature_names");
        for (Map.Entry<String, List<?>> entry : expected.entrySet()) {
            compareValue(
                    entry.getValue(), actual.get(entry.getKey()), tolerance,
                    path + "." + entry.getKey());
        }
    }

    private static void compareValue(
            Object expected,
            Object actual,
            double tolerance,
            String path) {
        if (expected instanceof Number && actual instanceof Number) {
            double expectedNumber = ((Number) expected).doubleValue();
            double actualNumber = ((Number) actual).doubleValue();
            if (Math.abs(expectedNumber - actualNumber) > tolerance) {
                throw mismatch(path, expected, actual);
            }
            return;
        }
        if (expected instanceof List<?> && actual instanceof List<?>) {
            List<?> expectedList = (List<?>) expected;
            List<?> actualList = (List<?>) actual;
            requireEqual(expectedList.size(), actualList.size(), path + ".size");
            for (int index = 0; index < expectedList.size(); index++) {
                compareValue(
                        expectedList.get(index), actualList.get(index), tolerance,
                        path + "[" + index + "]");
            }
            return;
        }
        if (!Objects.equals(expected, actual)) throw mismatch(path, expected, actual);
    }

    private static AssertionError mismatch(String path, Object expected, Object actual) {
        return new AssertionError(
                path + ": expected=" + expected + ", actual=" + actual);
    }

    private static void requireEqual(Object expected, Object actual, String path) {
        if (!Objects.equals(expected, actual)) throw mismatch(path, expected, actual);
    }

    private static String stringValue(Object value, String path) {
        if (!(value instanceof String) || ((String) value).isBlank()) {
            throw new IllegalArgumentException(path + " must be a non-blank string");
        }
        return (String) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectValue(Object value, String path) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return (Map<String, Object>) value;
    }

    private static List<Map<String, Object>> objectList(Object value, String path) {
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException(path + " must be an array");
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        List<?> list = (List<?>) value;
        for (int index = 0; index < list.size(); index++) {
            result.add(objectValue(list.get(index), path + "[" + index + "]"));
        }
        return result;
    }

    private static List<String> stringList(Object value, String path) {
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException(path + " must be an array");
        }
        List<String> result = new ArrayList<String>();
        List<?> list = (List<?>) value;
        for (int index = 0; index < list.size(); index++) {
            result.add(stringValue(list.get(index), path + "[" + index + "]"));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<?>> featureMap(Object value, String path) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        Map<?, ?> raw = (Map<?, ?>) value;
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof List<?>)) {
                throw new IllegalArgumentException(
                        path + " must map string feature names to arrays");
            }
        }
        return (Map<String, List<?>>) value;
    }
}
