package com.example.featuredag.config;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
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
        if (features == null) return List.of();
        // 容忍 null 元素：由 FeatureConfigLoader 产出带下标定位的校验错误，而非 List.copyOf 的裸 NPE
        return Collections.unmodifiableList(new ArrayList<>(features));
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
