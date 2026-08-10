package com.example.featuredag.demo;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.GenerateResult;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OfflineGenerateRequest;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.physical.ExecutionEnvironment;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DagDemo {
    private static final int THREE_DAYS_IN_SECONDS = 3 * 24 * 60 * 60;

    private static final String CONFIG_JSON = """
            {
              "features": [
                {"name":"auid","raw_name":"auid","type":"STRING","definition_type":"BASE",
                 "seq_max_length":1,"entity_scopes":[],"value_shape":null},
                {"name":"auid_app_time_seq","raw_name":"auid_app_time_seq","type":"STRING",
                 "definition_type":"BASE","seq_max_length":4,"entity_scopes":[],"value_shape":null},
                {"name":"timestamp","raw_name":"timestamp","type":"INT",
                 "definition_type":"BASE","seq_max_length":4,"entity_scopes":[],"value_shape":null},
                {"name":"request_time","raw_name":"request_time","type":"INT","definition_type":"BASE",
                 "seq_max_length":1,"entity_scopes":[],"value_shape":null},
                {"name":"target_app","raw_name":"target_app","type":"STRING","definition_type":"BASE",
                 "seq_max_length":1,"entity_scopes":[],"value_shape":null},
                {
                  "name":"auid_omnichannel_paid_cnt_3d",
                  "type":"INT",
                  "definition_type":"DERIVED",
                  "expression":"count(find_list_index_typed(list_index_typed(auid_app_time_seq, greater_in_sequence_typed(timestamp, request_time, {\\"margin\\":259200})), target_app))",
                  "output_policy":"OUTPUT",
                  "entity_scopes":["USER","SCENE"],
                  "value_shape":"SCALAR"
                },
                {
                  "name":"auid_appc3_omnichannel_paid_cnt_div10_365d",
                  "type":"INT",
                  "definition_type":"DERIVED",
                  "expression":"least(round(div_num(auid_omnichannel_paid_cnt_3d, {\\"divisor\\":10})), 1000)",
                  "output_policy":"OUTPUT",
                  "entity_scopes":["USER","SCENE"],
                  "value_shape":"SCALAR"
                },
                {
                  "name":"auid_appc3_omnichannel_paid_cnt_log_365d",
                  "type":"INT",
                  "definition_type":"DERIVED",
                  "expression":"least(round(div(log(auid_omnichannel_paid_cnt_3d), log(1.1))), 1000)",
                  "output_policy":"OUTPUT",
                  "entity_scopes":["USER","SCENE"],
                  "value_shape":"SCALAR"
                }
              ],
              "feature_set_name":"three_day_app_count",
              "version":"1"
            }
            """;

    private DagDemo() {}

    public static void main(String[] args) {
        Map<String, List<?>> row = Map.of(
                "auid", List.of("aaaa"),
                "auid_app_time_seq", List.of("app0", "app1", "app2", "app3"),
                "timestamp", List.of(1785549653L, 1785459831L, 1785286488L, 1785203315L),
                "request_time", List.of(1785549653),
                "target_app", List.of("app0"));

        InitOptions options = InitOptions.builder()
                .environment(ExecutionEnvironment.OFFLINE)
                .planId("three-day-app-count-demo")
                .rawFeatureScopes(Map.of("request_time", Set.of(EntityScope.SCENE)))
                .build();
        FeatureDagEngine engine = FeatureDagEngine.init(CONFIG_JSON, options);
        GenerateResult result = engine.generate(
                new OfflineGenerateRequest("auid-aaaa-row", row));

        long time3d = ((Number) row.get("request_time").getFirst()).longValue()
                - THREE_DAYS_IN_SECONDS;
        System.out.println("AUID: " + row.get("auid").getFirst());
        System.out.println("TIME_3D: " + time3d + " seconds since epoch");
        System.out.println("APP_SEQUENCE_SIZE: "
                + ((List<?>) row.get("auid_app_time_seq")).size());
        System.out.println("TIMESTAMP_SEQUENCE_SIZE: "
                + ((List<?>) row.get("timestamp")).size());
        System.out.println("TARGET_APP: " + row.get("target_app").getFirst());
        System.out.println("FEATURES: " + result.featureValues());
    }
}
