package com.example.featuredag.demo;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.GenerateResult;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OfflineGenerateRequest;
import com.example.featuredag.api.OnlineGenerateRequest;
import com.example.featuredag.physical.ExecutionEnvironment;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DagDemo {
    private static final int THREE_DAYS_IN_SECONDS = 3 * 24 * 60 * 60;
    private static final Set<String> TARGET_FEATURES = Set.of(
            "auid_omnichannel_paid_cnt_3d",
            "auid_appc3_omnichannel_paid_cnt_div10_365d",
            "auid_appc3_omnichannel_paid_cnt_log_365d");

    private DagDemo() {}

    public static void main(String[] args) {
        Map<String, List<?>> row = Map.of(
                "auid", List.of("aaaa"),
                "auid_app_time_seq", List.of("app0", "app1", "app2", "app3"),
                "timestamp", List.of(1785549653L, 1785459831L, 1785286488L, 1785203315L),
                "request_time", List.of(1785549653),
                "target_app", List.of("app0"));

        String configJson = DemoConfig.load();
        InitOptions offlineOptions = InitOptions.builder()
                .environment(ExecutionEnvironment.OFFLINE)
                .planId("three-day-app-count-demo")
                .targetFeatures(TARGET_FEATURES)
                .build();
        FeatureDagEngine offlineEngine = FeatureDagEngine.init(configJson, offlineOptions);
        GenerateResult offlineResult = offlineEngine.generate(
                new OfflineGenerateRequest("auid-aaaa-row", row));

        InitOptions onlineOptions = InitOptions.builder()
                .environment(ExecutionEnvironment.ONLINE)
                .planId("three-day-app-count-online-demo")
                .targetFeatures(TARGET_FEATURES)
                .build();
        FeatureDagEngine onlineEngine = FeatureDagEngine.init(configJson, onlineOptions);
        GenerateResult onlineResult = onlineEngine.generate(
                new OnlineGenerateRequest("auid-aaaa-request", row, List.of()));
        if (!offlineResult.featureValues().equals(onlineResult.featureValues())) {
            throw new IllegalStateException(
                    "Offline and online results differ: offline="
                            + offlineResult.featureValues()
                            + ", online=" + onlineResult.featureValues());
        }

        long time3d = ((Number) row.get("request_time").getFirst()).longValue()
                - THREE_DAYS_IN_SECONDS;
        System.out.println("AUID: " + row.get("auid").getFirst());
        System.out.println("TIME_3D: " + time3d + " seconds since epoch");
        System.out.println("APP_SEQUENCE_SIZE: "
                + ((List<?>) row.get("auid_app_time_seq")).size());
        System.out.println("TIMESTAMP_SEQUENCE_SIZE: "
                + ((List<?>) row.get("timestamp")).size());
        System.out.println("TARGET_APP: " + row.get("target_app").getFirst());
        System.out.println("FEATURES: " + offlineResult.featureValues());
        System.out.println("ONLINE_FEATURES: " + onlineResult.featureValues());
    }
}
