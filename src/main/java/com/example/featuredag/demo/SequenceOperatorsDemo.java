package com.example.featuredag.demo;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.GenerateResult;
import com.example.featuredag.api.OfflineGenerateRequest;

import java.util.List;
import java.util.Map;

/** Public-API demo for slice_by_indices, find_indices, zip_concat and calc_delta_seq. */
public final class SequenceOperatorsDemo {
    private SequenceOperatorsDemo() {}

    public static GenerateResult run() {
        FeatureDagEngine engine = FeatureDagEngine.init(
                InitialOperatorDemoConfig.load(),
                InitialOperatorDemoSupport.offlineOptions(
                        "initial-sequence-operators-demo",
                        "selected_codes",
                        "matching_indices",
                        "zipped_codes",
                        "number_deltas"));

        Map<String, List<?>> row = InitialOperatorDemoSupport.row();
        row.put("codes", InitialOperatorDemoSupport.sequence("A", "B", "A", "D"));
        row.put("slice_indices", InitialOperatorDemoSupport.sequence(1, 3));
        row.put("target_code", InitialOperatorDemoSupport.scalar("A"));
        row.put("labels", InitialOperatorDemoSupport.sequence("x", "y", "z", "w"));
        row.put("number_sequence", InitialOperatorDemoSupport.sequence(2.0, 5.0, 9.0));
        row.put("delta_base", InitialOperatorDemoSupport.scalar(10.0));

        GenerateResult result = engine.generate(
                new OfflineGenerateRequest("initial-sequence-operators-row", row));
        InitialOperatorDemoSupport.assertFeature(
                result.featureValues(),
                "selected_codes",
                InitialOperatorDemoSupport.sequence("B", "D"));
        InitialOperatorDemoSupport.assertFeature(
                result.featureValues(),
                "matching_indices",
                InitialOperatorDemoSupport.sequence(0, 2));
        InitialOperatorDemoSupport.assertFeature(
                result.featureValues(),
                "zipped_codes",
                InitialOperatorDemoSupport.sequence("A|x", "B|y", "A|z", "D|w"));
        InitialOperatorDemoSupport.assertFeature(
                result.featureValues(),
                "number_deltas",
                InitialOperatorDemoSupport.sequence(8.0, 5.0, 1.0));
        return result;
    }

    public static void main(String[] args) {
        GenerateResult result = run();
        InitialOperatorDemoSupport.printResult("SEQUENCE OPERATORS", result.featureValues());
    }
}
