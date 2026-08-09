package com.example.featuredag.demo;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.GenerateResult;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OfflineGenerateRequest;

import java.util.List;
import java.util.Map;

public final class DagDemo {
    private static final int THREE_DAYS_IN_SECONDS = 3 * 24 * 60 * 60;

    private static final String CONFIG_JSON = """
            {
              "features": [
                {"name":"auid","raw_name":"auid","type":"STRING","definition_type":"BASE",
                 "entity_scopes":["USER"],"value_shape":"SCALAR"},
                {"name":"auid_app_time_seq","raw_name":"auid_app_time_seq","type":"EVENT_SEQUENCE",
                 "definition_type":"BASE","entity_scopes":["USER"],"value_shape":"SEQUENCE"},
                {"name":"timestamp","raw_name":"timestamp","type":"EVENT_SEQUENCE",
                 "definition_type":"BASE","entity_scopes":["USER"],"value_shape":"SEQUENCE"},
                {"name":"request_time","raw_name":"request_time","type":"INT","definition_type":"BASE",
                 "entity_scopes":["SCENE"],"value_shape":"SCALAR"},
                {"name":"target_app","raw_name":"target_app","type":"STRING","definition_type":"BASE",
                 "entity_scopes":["USER"],"value_shape":"SCALAR"},
                {
                  "name":"auid_omnichannel_paid_cnt_3d",
                  "type":"INT",
                  "definition_type":"DERIVED",
                  "expression":"count(find_list_index_typed(list_index_typed(auid_app_time_seq, greater_in_sequence_typed(timestamp, request_time, {\\"margin\\":259200})), target_app))",
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
                "auid_app_time_seq", List.of(
                        "app0", "app1", "app2", "app3", "app4", "app5", "app6", "app7"),
                "timestamp", List.of(
                        1785549653L, 1785459831L, 1785286488L, 1785203315L,
                        1785114236L, 1785025362L, 1784938978L, 1784856870L),
                "request_time", List.of(1785549653),
                "target_app", List.of("app0"));

        FeatureDagEngine engine = FeatureDagEngine.init(
                CONFIG_JSON, InitOptions.offline("three-day-app-count-demo"));
        GenerateResult result = engine.generate(
                new OfflineGenerateRequest("auid-aaaa-row", row));

        long time3d = ((Number) row.get("request_time").getFirst()).longValue()
                - THREE_DAYS_IN_SECONDS;
        System.out.println("AUID: " + row.get("auid"));
        System.out.println("TIME_3D: " + time3d + " seconds since epoch");
        System.out.println("APP_SEQUENCE_SIZE: "
                + ((List<?>) row.get("auid_app_time_seq")).size());
        System.out.println("TIMESTAMP_SEQUENCE_SIZE: "
                + ((List<?>) row.get("timestamp")).size());
        System.out.println("TARGET_APP: " + row.get("target_app"));
        System.out.println("FEATURES: " + result.featureValues());
    }
}
