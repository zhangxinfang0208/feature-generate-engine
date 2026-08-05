package com.example.featuredag.demo;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.GenerateResult;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OfflineGenerateRequest;
import com.example.featuredag.api.OnlineGenerateRequest;
import com.example.featuredag.runtime.SequenceBlock;
import com.example.featuredag.runtime.SequenceEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DagDemo {
    private static final String CONFIG_JSON = """
            {
              "features": [
                {"name":"user_click_count","raw_name":"user_click_count","type":"INT","dft":0,"entity_scopes":["USER"]},
                {"name":"user_seq1","raw_name":"user_seq1","type":"EVENT_SEQUENCE","entity_scopes":["USER"]},
                {"name":"item_industry","raw_name":"item_industry","type":"STRING","dft":"unknown","entity_scopes":["ITEM"]},
                {"name":"item_price","raw_name":"item_price","type":"DOUBLE","dft":0.0,"entity_scopes":["ITEM"]}
              ],
              "derivedFeatures": [
                {
                  "name":"user_click_score",
                  "type":"DOUBLE",
                  "expression":"normalize(coalesce(user_click_count, 0), {\\"min\\":0,\\"max\\":100})",
                  "output_policy":"INTERNAL_ONLY"
                },
                {
                  "name":"same_industry_seq",
                  "type":"EVENT_SEQUENCE",
                  "expression":"extractIndustry(user_seq1, item_industry)",
                  "output_policy":"INTERNAL_ONLY"
                },
                {
                  "name":"same_industry_count",
                  "store_name":"same_industry_count",
                  "type":"INT",
                  "expression":"count(same_industry_seq)",
                  "output_policy":"OUTPUT",
                  "order":1
                },
                {
                  "name":"item_price_log",
                  "type":"DOUBLE",
                  "expression":"log(add(item_price, 1))",
                  "output_policy":"INTERNAL_ONLY"
                },
                {
                  "name":"final_score",
                  "store_name":"final_score",
                  "type":"DOUBLE",
                  "expression":"multiply(user_click_score, item_price_log)",
                  "output_policy":"OUTPUT",
                  "order":2
                }
              ],
              "feature_set_name":"demo_features",
              "version":"latest"
            }
            """;

    private DagDemo() {}

    public static void main(String[] args) {
        SequenceBlock sequence = sampleSequence();
        FeatureDagEngine offlineEngine = FeatureDagEngine.init(
                CONFIG_JSON, InitOptions.offline("offline-demo"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("user_click_count", 10);
        row.put("user_seq1", sequence);
        row.put("item_industry", "industry1");
        row.put("item_price", 100.0);
        GenerateResult offline = offlineEngine.generate(
                new OfflineGenerateRequest("offline-row-1", row));
        System.out.println("OFFLINE: " + offline.featureValues());

        FeatureDagEngine onlineEngine = FeatureDagEngine.init(
                CONFIG_JSON, InitOptions.online("online-demo"));
        GenerateResult online = onlineEngine.generate(new OnlineGenerateRequest(
                "request-1",
                Map.of("user_click_count", 10, "user_seq1", sequence),
                List.of(
                        Map.of("item_industry", "industry1", "item_price", 100.0),
                        Map.of("item_industry", "industry2", "item_price", 50.0),
                        Map.of("item_industry", "industry1", "item_price", 80.0))));
        System.out.println("ONLINE_SHARED: " + online.featureValues());
        System.out.println("ONLINE_CANDIDATES: " + online.candidateFeatureValues());
    }

    private static SequenceBlock sampleSequence() {
        return new SequenceBlock(
                "user1-main-seq",
                1L,
                List.of(
                        new SequenceEvent("history-item-1", "industry1", 1_000L, "click", 1.0),
                        new SequenceEvent("history-item-2", "industry2", 2_000L, "click", 1.0),
                        new SequenceEvent("history-item-3", "industry1", 3_000L, "view", 1.0),
                        new SequenceEvent("history-item-4", "industry3", 4_000L, "click", 1.0),
                        new SequenceEvent("history-item-5", "industry1", 5_000L, "buy", 1.0)));
    }
}
