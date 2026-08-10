package com.example.featuredag.demo;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OfflineBatchGenerateRequest;
import com.example.featuredag.api.OfflineBatchGenerateResult;
import com.example.featuredag.physical.ExecutionEnvironment;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 使用真实公共 API 展示离线多行 Batch 特征生成。 */
public final class OfflineBatchDemo {
    private static final Set<String> TARGET_FEATURES = Set.of(
            "user_tag_count",
            "score",
            "matching_tag_count");

    private OfflineBatchDemo() {}

    public static OfflineBatchGenerateResult run() {
        InitOptions options = InitOptions.builder()
                .environment(ExecutionEnvironment.OFFLINE)
                .planId("offline-batch-demo-plan")
                .targetFeatures(TARGET_FEATURES)
                .build();
        FeatureDagEngine engine = FeatureDagEngine.init(DemoConfig.load(), options);
        List<Map<String, List<?>>> rows = List.of(
                Map.of(
                        "user_multiplier", List.of(2.0),
                        "user_tags", List.of("sports", "music", "sports"),
                        "item_value", List.of(10.0),
                        "item_tag", List.of("sports")),
                Map.of(
                        "user_multiplier", List.of(2.0),
                        "user_tags", List.of("sports", "music", "sports"),
                        "item_value", List.of(5.0),
                        "item_tag", List.of("music")),
                Map.of(
                        "user_multiplier", List.of(3.0),
                        "user_tags", List.of("music", "music", "news"),
                        "item_value", List.of(4.0),
                        "item_tag", List.of("music")));
        return engine.generateBatch(
                new OfflineBatchGenerateRequest("offline-batch-demo", rows));
    }

    public static void main(String[] args) {
        OfflineBatchGenerateResult result = run();
        System.out.println("OFFLINE_BATCH_ID: " + result.executionId());
        System.out.println("ROW_COUNT: " + result.rows().size());
        for (int rowIndex = 0; rowIndex < result.rows().size(); rowIndex++) {
            System.out.println("ROW[" + rowIndex + "]: " + result.rows().get(rowIndex));
        }
    }
}
