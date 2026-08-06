package com.example.featuredag.config;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FeatureSetConfig {
    @JsonProperty("features")
    private List<FeatureConfig> features = List.of();

    @JsonProperty("feature_set_name")
    private String featureSetName;

    @JsonProperty("version")
    private String version;

    private final Map<String, Object> additionalProperties = new LinkedHashMap<>();

    public List<FeatureConfig> features() {
        return features == null ? List.of() : List.copyOf(features);
    }

    public String featureSetName() {
        return featureSetName;
    }

    public String version() {
        return version;
    }

    public Map<String, Object> additionalProperties() {
        return Collections.unmodifiableMap(additionalProperties);
    }

    @JsonAnySetter
    void addAdditionalProperty(String name, Object value) {
        additionalProperties.put(name, value);
    }
}
