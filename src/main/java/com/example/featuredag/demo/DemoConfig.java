package com.example.featuredag.demo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 为所有可运行 Demo 提供同一份特征配置。 */
final class DemoConfig {
    static final String RESOURCE_PATH = "/demo/config.json";

    private DemoConfig() {}

    static String load() {
        try (InputStream input = DemoConfig.class.getResourceAsStream(RESOURCE_PATH)) {
            if (input != null) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            Path sourcePath = Path.of("src", "main", "resources", "demo", "config.json");
            return Files.readString(sourcePath, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to load shared Demo config " + RESOURCE_PATH,
                    exception);
        }
    }
}
