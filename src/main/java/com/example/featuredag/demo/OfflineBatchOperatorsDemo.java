package com.example.featuredag.demo;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.OfflineBatchGenerateRequest;
import com.example.featuredag.api.OfflineBatchGenerateResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Public-API demo that evaluates all eight initial operators for multiple offline rows. */
public final class OfflineBatchOperatorsDemo {
    private OfflineBatchOperatorsDemo() {}

    public static OfflineBatchGenerateResult run() {
        FeatureDagEngine engine = FeatureDagEngine.init(
                InitialOperatorDemoConfig.load(),
                InitialOperatorDemoSupport.offlineOptions(
                        "initial-operators-offline-batch-demo",
                        "number_bucket",
                        "logarithm_base2",
                        "codes_length",
                        "distinct_codes",
                        "selected_codes",
                        "matching_indices",
                        "zipped_codes",
                        "number_deltas"));

        List<Map<String, List<?>>> rows = new ArrayList<Map<String, List<?>>>();
        rows.add(firstRow());
        rows.add(secondRow());
        OfflineBatchGenerateResult result = engine.generateBatch(
                new OfflineBatchGenerateRequest("initial-operators-offline-batch", rows));

        Map<String, List<?>> first = result.rows().get(0);
        InitialOperatorDemoSupport.assertFeature(
                first, "number_bucket", InitialOperatorDemoSupport.scalar(2));
        InitialOperatorDemoSupport.assertFeature(
                first, "matching_indices", InitialOperatorDemoSupport.sequence(0, 2));
        InitialOperatorDemoSupport.assertFeature(
                first, "number_deltas", InitialOperatorDemoSupport.sequence(-8.0, -5.0, -1.0));

        Map<String, List<?>> second = result.rows().get(1);
        InitialOperatorDemoSupport.assertFeature(
                second, "number_bucket", InitialOperatorDemoSupport.scalar(3));
        InitialOperatorDemoSupport.assertFeature(
                second, "logarithm_base2", InitialOperatorDemoSupport.scalar(5.0));
        InitialOperatorDemoSupport.assertFeature(
                second, "selected_codes", InitialOperatorDemoSupport.sequence("Y", "X"));
        InitialOperatorDemoSupport.assertFeature(
                second, "number_deltas", InitialOperatorDemoSupport.sequence(5.0, 3.0, -1.0));
        return result;
    }

    private static Map<String, List<?>> firstRow() {
        Map<String, List<?>> row = InitialOperatorDemoSupport.row();
        row.put("number_value", InitialOperatorDemoSupport.scalar(16.0));
        row.put("logarithm_value", InitialOperatorDemoSupport.scalar(8.0));
        row.put("codes", InitialOperatorDemoSupport.sequence("A", "B", "A", "D"));
        row.put("slice_indices", InitialOperatorDemoSupport.sequence(1, 3));
        row.put("target_code", InitialOperatorDemoSupport.scalar("A"));
        row.put("labels", InitialOperatorDemoSupport.sequence("x", "y", "z", "w"));
        row.put("number_sequence", InitialOperatorDemoSupport.sequence(2.0, 5.0, 9.0));
        row.put("delta_base", InitialOperatorDemoSupport.scalar(10.0));
        return row;
    }

    private static Map<String, List<?>> secondRow() {
        Map<String, List<?>> row = InitialOperatorDemoSupport.row();
        row.put("number_value", InitialOperatorDemoSupport.scalar(150.0));
        row.put("logarithm_value", InitialOperatorDemoSupport.scalar(32.0));
        row.put("codes", InitialOperatorDemoSupport.sequence("X", "Y", "X"));
        row.put("slice_indices", InitialOperatorDemoSupport.sequence(1, 2));
        row.put("target_code", InitialOperatorDemoSupport.scalar("X"));
        row.put("labels", InitialOperatorDemoSupport.sequence("p", "q", "r"));
        row.put("number_sequence", InitialOperatorDemoSupport.sequence(10.0, 8.0, 4.0));
        row.put("delta_base", InitialOperatorDemoSupport.scalar(5.0));
        return row;
    }

    public static void main(String[] args) {
        OfflineBatchGenerateResult result = run();
        System.out.println("=== OFFLINE BATCH: " + result.executionId() + " ===");
        for (int rowIndex = 0; rowIndex < result.rows().size(); rowIndex++) {
            InitialOperatorDemoSupport.printResult(
                    "ROW " + rowIndex, result.rows().get(rowIndex));
        }
    }
}
