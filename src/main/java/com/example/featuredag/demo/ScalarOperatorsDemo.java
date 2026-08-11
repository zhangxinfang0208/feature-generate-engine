package com.example.featuredag.demo;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.GenerateResult;
import com.example.featuredag.api.OfflineGenerateRequest;

import java.util.List;
import java.util.Map;

/** Public-API demo for discrete, log_base, get_seq_length and count_distinct. */
public final class ScalarOperatorsDemo {
    private ScalarOperatorsDemo() {}

    public static GenerateResult run() {
        FeatureDagEngine engine = FeatureDagEngine.init(
                InitialOperatorDemoConfig.load(),
                InitialOperatorDemoSupport.offlineOptions(
                        "initial-scalar-operators-demo",
                        "number_bucket",
                        "logarithm_base2",
                        "codes_length",
                        "distinct_codes"));

        Map<String, List<?>> row = InitialOperatorDemoSupport.row();
        row.put("number_value", InitialOperatorDemoSupport.scalar(16.0));
        row.put("logarithm_value", InitialOperatorDemoSupport.scalar(8.0));
        row.put("codes", InitialOperatorDemoSupport.sequence("A", "B", "A", "D"));

        GenerateResult result = engine.generate(
                new OfflineGenerateRequest("initial-scalar-operators-row", row));
        InitialOperatorDemoSupport.assertFeature(
                result.featureValues(), "number_bucket", InitialOperatorDemoSupport.scalar(2));
        InitialOperatorDemoSupport.assertFeature(
                result.featureValues(), "logarithm_base2", InitialOperatorDemoSupport.scalar(3.0));
        InitialOperatorDemoSupport.assertFeature(
                result.featureValues(), "codes_length", InitialOperatorDemoSupport.scalar(4));
        InitialOperatorDemoSupport.assertFeature(
                result.featureValues(), "distinct_codes", InitialOperatorDemoSupport.scalar(3));
        return result;
    }

    public static void main(String[] args) {
        GenerateResult result = run();
        InitialOperatorDemoSupport.printResult("SCALAR OPERATORS", result.featureValues());
    }
}
