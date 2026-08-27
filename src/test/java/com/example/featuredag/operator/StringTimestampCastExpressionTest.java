package com.example.featuredag.operator;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.GenerateResult;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OnlineGenerateRequest;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/** 页面侧 STRING 时间戳经显式数值转换后参与同类目序列计算的端到端测试。 */
public final class StringTimestampCastExpressionTest {
    @Test
    public void stringImpressionTimeCanBeExplicitlyCastForDeltaSequence() {
        FeatureDagEngine engine = FeatureDagEngine.init(
                featureConfig(), InitOptions.online("same-cate-string-time-plan"));

        Map<String, List<?>> shared = new LinkedHashMap<String, List<?>>();
        shared.put("cate_seq", Arrays.asList("sports", "games", "sports"));
        shared.put("cluster_seq", Arrays.asList("c1", "c2", "c3"));
        shared.put("slot_seq", Arrays.asList("s1", "s2", "s3"));
        shared.put("timestamp_seq", Arrays.asList(1_000_000, 1_060_000, 1_090_000));
        shared.put("impr_time", Arrays.asList("1120000"));

        GenerateResult result = engine.generate(new OnlineGenerateRequest(
                "same-cate-string-time",
                shared,
                Arrays.asList(candidate("sports"), candidate("games"))));

        assertEquals(
                Arrays.asList("c1^sports^s1^2.0", "c3^sports^s3^0.5"),
                result.candidateFeatureValues().get(0).get("same_cate_seq"));
        assertEquals(
                Arrays.asList("c2^games^s2^1.0"),
                result.candidateFeatureValues().get(1).get("same_cate_seq"));
    }

    private static Map<String, List<?>> candidate(String tag1id) {
        Map<String, List<?>> result = new LinkedHashMap<String, List<?>>();
        result.put("tag1id", Arrays.asList(tag1id));
        return result;
    }

    private static String featureConfig() {
        return "{"
                + "\"feature_set_name\":\"same-cate-seq-inline-probe\","
                + "\"version\":\"1\","
                + "\"features\":["
                + raw("cate_seq", "STRING", "USER", "SEQUENCE") + ","
                + raw("cluster_seq", "STRING", "USER", "SEQUENCE") + ","
                + raw("slot_seq", "STRING", "USER", "SEQUENCE") + ","
                + raw("timestamp_seq", "INT", "USER", "SEQUENCE") + ","
                + raw("impr_time", "STRING", "USER", "SCALAR") + ","
                + raw("tag1id", "STRING", "ITEM", "SCALAR") + ","
                + "{\"name\":\"same_cate_seq\",\"type\":\"STRING\","
                + "\"definition_type\":\"DERIVED\","
                + "\"expression\":\"zip_concat("
                + "slice_by_indices(cluster_seq, find_indices(cate_seq, tag1id)), "
                + "slice_by_indices(cate_seq, find_indices(cate_seq, tag1id)), "
                + "slice_by_indices(slot_seq, find_indices(cate_seq, tag1id)), "
                + "slice_by_indices(calc_delta_seq(timestamp_seq, to_bigint(impr_time), "
                + "{\\\"divisor\\\":60000}), find_indices(cate_seq, tag1id)), "
                + "{\\\"delimiter\\\":\\\"^\\\"})\","
                + "\"output_policy\":\"OUTPUT\","
                + "\"entity_scopes\":[\"USER\",\"ITEM\"],"
                + "\"value_shape\":\"SEQUENCE\"}]}";
    }

    private static String raw(String name, String type, String scope, String shape) {
        return "{\"name\":\"" + name + "\",\"raw_name\":\"" + name + "\","
                + "\"type\":\"" + type + "\",\"definition_type\":\"BASE\","
                + "\"entity_scopes\":[\"" + scope + "\"],"
                + "\"value_shape\":\"" + shape + "\"}";
    }
}
