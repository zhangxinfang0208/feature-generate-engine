package com.example.featuredag.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class FeatureConfigLoader {
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private FeatureConfigLoader() {}

    public static FeatureSetConfig load(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Feature config JSON must not be blank");
        }
        try {
            return OBJECT_MAPPER.readValue(json, FeatureSetConfig.class);
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
}
