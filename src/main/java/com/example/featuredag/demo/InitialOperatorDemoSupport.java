package com.example.featuredag.demo;

import com.example.featuredag.api.InitOptions;
import com.example.featuredag.physical.ExecutionEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Small Java 8-compatible helpers shared by the demo entry points. */
final class InitialOperatorDemoSupport {
    private InitialOperatorDemoSupport() {}

    static InitOptions offlineOptions(String planId, String... targetFeatures) {
        return InitOptions.builder()
                .environment(ExecutionEnvironment.OFFLINE)
                .planId(planId)
                .targetFeatures(new LinkedHashSet<String>(Arrays.asList(targetFeatures)))
                .build();
    }

    static <T> List<T> scalar(T value) {
        return Collections.singletonList(value);
    }

    @SafeVarargs
    static <T> List<T> sequence(T... values) {
        return Collections.unmodifiableList(new ArrayList<T>(Arrays.asList(values)));
    }

    static Map<String, List<?>> row() {
        return new LinkedHashMap<String, List<?>>();
    }

    static void assertFeature(
            Map<String, List<?>> actual,
            String featureName,
            List<?> expected) {
        List<?> value = actual.get(featureName);
        if (!expected.equals(value)) {
            throw new IllegalStateException(
                    "Unexpected output for " + featureName
                            + ": expected=" + expected + ", actual=" + value);
        }
    }

    static void printResult(String title, Map<String, List<?>> values) {
        System.out.println("=== " + title + " ===");
        for (Map.Entry<String, List<?>> entry : values.entrySet()) {
            System.out.println(entry.getKey() + " = " + entry.getValue());
        }
    }
}
