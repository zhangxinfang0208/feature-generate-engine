package com.example.featuredag.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class FeatureConfigLoader {
    private static final Set<String> TOP_LEVEL_PROPERTIES = Set.of(
            "features", "feature_set_name", "version");
    private static final Set<String> FEATURE_PROPERTIES = Set.of(
            "name",
            "raw_name",
            "store_name",
            "type",
            "definition_type",
            "expression",
            "output_policy",
            "dft",
            "to_use",
            "order",
            "is_feedback",
            "entity_scopes",
            "value_shape",
            "seq_max_length",
            "description");
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private FeatureConfigLoader() {}

    public static FeatureSetConfig load(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Feature config JSON must not be blank");
        }
        try {
            FeatureSetConfig config = OBJECT_MAPPER.readValue(json, FeatureSetConfig.class);
            if (config.additionalProperties().containsKey("derivedFeatures")) {
                throw new IllegalArgumentException(
                        "Obsolete top-level property derivedFeatures is not supported; use features");
            }
            rejectLikelyTypos(
                    config.additionalProperties(), TOP_LEVEL_PROPERTIES, "top-level");
            for (int index = 0; index < config.features().size(); index++) {
                FeatureConfig feature = config.features().get(index);
                if (feature != null) {
                    rejectLikelyTypos(
                            feature.additionalProperties(),
                            FEATURE_PROPERTIES,
                            "feature at index " + index);
                }
            }
            return config;
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException(
                    "Invalid feature config JSON: " + error.getOriginalMessage(), error);
        }
    }

    public static FeatureSetConfig load(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            return load(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new IllegalArgumentException("Failed to read feature config: " + path, error);
        }
    }

    /**
     * 业务扩展字段继续保留；与引擎核心字段高度相似的未知字段按拼写错误拒绝，
     * 避免 entity_scop/value_shap 等配置静默改变构图语义。
     */
    private static void rejectLikelyTypos(
            Map<String, Object> additionalProperties,
            Set<String> knownProperties,
            String location) {
        for (String property : additionalProperties.keySet()) {
            String suggestion = closestLikelyProperty(property, knownProperties);
            if (suggestion != null) {
                throw new IllegalArgumentException(
                        "Unknown " + location + " property '" + property
                                + "'; did you mean '" + suggestion + "'?");
            }
        }
    }

    private static String closestLikelyProperty(
            String property,
            Set<String> knownProperties) {
        if (property == null || property.isBlank()) return null;
        String closest = null;
        int closestDistance = Integer.MAX_VALUE;
        for (String known : knownProperties) {
            int distance = editDistance(property, known);
            if (distance < closestDistance) {
                closest = known;
                closestDistance = distance;
            }
        }
        if (closest == null) return null;
        int allowedDistance = closest.length() >= 8 ? 2 : 1;
        return closestDistance <= allowedDistance ? closest : null;
    }

    private static int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int column = 0; column <= right.length(); column++) previous[column] = column;
        for (int row = 1; row <= left.length(); row++) {
            current[0] = row;
            for (int column = 1; column <= right.length(); column++) {
                int substitution = previous[column - 1]
                        + (left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1);
                current[column] = Math.min(
                        Math.min(previous[column] + 1, current[column - 1] + 1),
                        substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }
}
