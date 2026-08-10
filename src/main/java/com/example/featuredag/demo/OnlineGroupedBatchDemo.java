package com.example.featuredag.demo;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.GenerateResult;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OnlineBatchGenerateRequest;
import com.example.featuredag.api.OnlineBatchGenerateResult;
import com.example.featuredag.api.OnlineRequestGroup;
import com.example.featuredag.physical.ExecutionEnvironment;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 使用真实公共 API 展示在线多 user 分组 Batch 特征生成。 */
public final class OnlineGroupedBatchDemo {
    private static final Set<String> TARGET_FEATURES = Set.of(
            "user_tag_count",
            "score",
            "matching_tag_count");

    private OnlineGroupedBatchDemo() {}

    public static OnlineBatchGenerateResult run() {
        InitOptions options = InitOptions.builder()
                .environment(ExecutionEnvironment.ONLINE)
                .planId("online-grouped-batch-demo-plan")
                .targetFeatures(TARGET_FEATURES)
                .build();
        FeatureDagEngine engine = FeatureDagEngine.init(DemoConfig.load(), options);
        List<OnlineRequestGroup> groups = List.of(
                new OnlineRequestGroup(
                        "user-a",
                        Map.of(
                                "user_multiplier", List.of(2.0),
                                "user_tags", List.of("sports", "music", "sports")),
                        List.of(
                                Map.of(
                                        "item_value", List.of(10.0),
                                        "item_tag", List.of("sports")),
                                Map.of(
                                        "item_value", List.of(5.0),
                                        "item_tag", List.of("music")))),
                new OnlineRequestGroup(
                        "user-b",
                        Map.of(
                                "user_multiplier", List.of(3.0),
                                "user_tags", List.of("music", "music", "news")),
                        List.of(
                                Map.of(
                                        "item_value", List.of(4.0),
                                        "item_tag", List.of("music")))),
                new OnlineRequestGroup(
                        "user-empty",
                        Map.of(
                                "user_multiplier", List.of(4.0),
                                "user_tags", List.of()),
                        List.of()));
        return engine.generateBatch(
                new OnlineBatchGenerateRequest("online-grouped-batch-demo", groups));
    }

    public static void main(String[] args) {
        OnlineBatchGenerateResult result = run();
        System.out.println("ONLINE_BATCH_ID: " + result.executionId());
        System.out.println("GROUP_COUNT: " + result.groupResults().size());
        for (int groupIndex = 0; groupIndex < result.groupResults().size(); groupIndex++) {
            GenerateResult group = result.groupResults().get(groupIndex);
            System.out.println("GROUP[" + groupIndex + "] ID: " + group.executionId());
            System.out.println("  SHARED: " + group.featureValues());
            System.out.println("  CANDIDATES: " + group.candidateFeatureValues());
        }
    }
}
