package com.example.featuredag.demo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Loads the shared configuration used by the initial-operator demos. */
final class InitialOperatorDemoConfig {
    private static final String RESOURCE = "/demo/initial-operators.json";

    private InitialOperatorDemoConfig() {}

    static String load() {
        InputStream stream = InitialOperatorDemoConfig.class.getResourceAsStream(RESOURCE);
        if (stream == null) {
            throw new IllegalStateException("Missing demo resource: " + RESOURCE);
        }
        StringBuilder json = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[2048];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                json.append(buffer, 0, count);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Failed to read demo resource: " + RESOURCE, error);
        }
        return json.toString();
    }
}
