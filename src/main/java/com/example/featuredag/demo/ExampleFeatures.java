package com.example.featuredag.demo;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.FeatureDefinition;
import com.example.featuredag.definition.OutputPolicy;

import java.util.List;
import java.util.Set;

public final class ExampleFeatures {
    private ExampleFeatures() {}

    public static List<FeatureDefinition> definitions() {
        return List.of(
                FeatureDefinition.raw("user_click_count", DataType.INT, EntityScope.USER, 0),
                FeatureDefinition.raw("user_seq1", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                FeatureDefinition.raw("item_industry", DataType.STRING, EntityScope.ITEM, "unknown"),
                FeatureDefinition.raw("item_price", DataType.DOUBLE, EntityScope.ITEM, 0.0),

                FeatureDefinition.derived(
                        "user_click_score",
                        DataType.DOUBLE,
                        "normalize(coalesce(user_click_count, 0), {\"method\":\"min_max\",\"min\":0,\"max\":100})",
                        OutputPolicy.OUTPUT),

                FeatureDefinition.derived(
                        "same_industry_seq",
                        DataType.EVENT_SEQUENCE,
                        "extractIndustry(user_seq1, item_industry)",
                        OutputPolicy.OUTPUT),

                FeatureDefinition.derived(
                        "same_industry_count",
                        DataType.INT,
                        "count(same_industry_seq)",
                        OutputPolicy.OUTPUT),

                FeatureDefinition.derived(
                        "item_price_log",
                        DataType.DOUBLE,
                        "log(add(item_price, 1))",
                        OutputPolicy.OUTPUT),

                FeatureDefinition.derived(
                        "final_score",
                        DataType.DOUBLE,
                        "multiply(user_click_score, item_price_log)",
                        OutputPolicy.OUTPUT)
        );
    }

    public static Set<String> transformTargets() {
        return Set.of(
                "user_click_score",
                "same_industry_seq",
                "same_industry_count",
                "item_price_log",
                "final_score");
    }

    public static Set<String> onlineTargets() {
        return Set.of("same_industry_count", "final_score");
    }
}
