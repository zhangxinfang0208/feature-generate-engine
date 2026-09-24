package com.example.featuredag.performance;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OnlineGenerateRequest;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.runtime.ObservabilityOptions;
import com.example.featuredag.runtime.ExecutionDiagnostics;
import com.example.featuredag.runtime.RuntimeObserver;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Test-only public API fixture. Every base participates in at least one target. */
public final class LargeOnlineWorkload {
    private final String config;
    private final Set<String> targets = new LinkedHashSet<String>();
    private final List<OnlineGenerateRequest> requests = new ArrayList<OnlineGenerateRequest>();
    private volatile ExecutionDiagnostics latestDiagnostics;

    public LargeOnlineWorkload(int sequenceLength, int candidates, int requestPool) throws Exception {
        if (sequenceLength < 1 || candidates < 1 || requestPool < 1) {
            throw new IllegalArgumentException("sequenceLength, candidates and requestPool must be positive");
        }
        List<Map<String, Object>> features = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 320; i++) {
            Map<String, Object> feature = feature("b" + i, "BASE", i < 160 ? "SEQUENCE" : "SCALAR");
            feature.put("raw_name", "b" + i);
            feature.put("entity_scopes", Collections.singletonList(i < 200 ? "USER" : i < 240 ? "SCENE" : "ITEM"));
            features.add(feature);
        }
        for (int i = 0; i < 320; i++) {
            String item = "b" + (240 + i % 80);
            String expression;
            if (i < 160) {
                String selection = "find_indices(b" + i + ", " + item + ")";
                switch (i % 4) {
                    case 0: expression = "get_seq_length(" + selection + ")"; break;
                    case 1: expression = "count_distinct(slice_by_indices(b" + i + ", " + selection + "))"; break;
                    case 2: expression = "add(count_distinct(b" + i + "), " + item + ")"; break;
                    default: expression = "slice_by_indices(b" + i + ", " + selection + ")"; break;
                }
            } else {
                expression = "add(mul(b" + i + ", 2), " + item + ")";
            }
            Map<String, Object> feature = feature("d" + i, "DERIVED", i < 160 && i % 4 == 3 ? "SEQUENCE" : "SCALAR");
            feature.put("expression", expression);
            feature.put("output_policy", "OUTPUT");
            feature.put("order", i);
            features.add(feature);
            targets.add("d" + i);
        }
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("feature_set_name", "large_online_performance");
        root.put("version", "1");
        root.put("features", features);
        config = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(root);
        for (int requestIndex = 0; requestIndex < requestPool; requestIndex++) {
            Map<String, List<?>> shared = new LinkedHashMap<String, List<?>>();
            for (int i = 0; i < 240; i++) {
                List<Integer> values = new ArrayList<Integer>();
                if (i < 160) {
                    for (int j = 0; j < sequenceLength; j++) values.add(1 + (j * 17 + i * 7 + requestIndex * 11) % 64);
                } else {
                    values.add(1 + (i + requestIndex * 7) % 64);
                }
                shared.put("b" + i, values);
            }
            List<Map<String, List<?>>> rows = new ArrayList<Map<String, List<?>>>();
            for (int row = 0; row < candidates; row++) {
                Map<String, List<?>> candidate = new LinkedHashMap<String, List<?>>();
                for (int i = 240; i < 320; i++) {
                    candidate.put("b" + i, Collections.singletonList(1 + (row * 13 + i * 3 + requestIndex * 5) % 64));
                }
                rows.add(candidate);
            }
            requests.add(new OnlineGenerateRequest("pool-" + requestIndex, shared, rows));
        }
    }

    private static Map<String, Object> feature(String name, String kind, String shape) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("name", name);
        result.put("definition_type", kind);
        result.put("type", "INT");
        result.put("value_shape", shape);
        return result;
    }

    public FeatureDagEngine engine(boolean observe) {
        return FeatureDagEngine.init(config, InitOptions.builder()
                .environment(ExecutionEnvironment.ONLINE).planId("large-online-performance")
                .targetFeatures(targets)
                .runtimeObserver(observe ? diagnostics -> latestDiagnostics = diagnostics : RuntimeObserver.noop())
                .observabilityOptions(ObservabilityOptions.builder().enabled(observe).build()).build());
    }

    /** Independent Java oracle, deliberately does not call any engine operator. */
    public Map<String, List<?>> expected(int requestIndex, int row) {
        OnlineGenerateRequest request = request(requestIndex);
        Map<String, List<?>> result = new LinkedHashMap<String, List<?>>();
        for (int i = 0; i < 320; i++) {
            int item = (Integer) request.candidates().get(row).get("b" + (240 + i % 80)).get(0);
            List<?> input = i < 240 ? request.sharedValues().get("b" + i) : request.candidates().get(row).get("b" + i);
            if (i < 160) {
                List<Object> matches = new ArrayList<Object>();
                for (Object value : input) if (((Integer) value).intValue() == item) matches.add(value);
                switch (i % 4) {
                    case 0: result.put("d" + i, Collections.singletonList(matches.size())); break;
                    case 1: result.put("d" + i, Collections.singletonList(matches.isEmpty() ? 0 : 1)); break;
                    case 2: result.put("d" + i, Collections.singletonList((long) new LinkedHashSet<Object>(input).size() + item)); break;
                    default: result.put("d" + i, matches); break;
                }
            } else {
                result.put("d" + i, Collections.singletonList((long) (Integer) input.get(0) * 2 + item));
            }
        }
        return result;
    }

    public int baseCount() { return 320; }
    public int sequenceCount() { return 160; }
    public String config() { return config; }
    public ExecutionDiagnostics latestDiagnostics() { return latestDiagnostics; }
    public Set<String> targets() { return Collections.unmodifiableSet(targets); }
    public OnlineGenerateRequest request(int index) { return requests.get(index % requests.size()); }
}
