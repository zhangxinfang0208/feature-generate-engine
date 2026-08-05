package com.example.featuredag.config;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RawFeatureConfig {
    @JsonProperty("name")
    private String name;

    @JsonProperty("raw_name")
    private String rawName;

    @JsonProperty("store_name")
    private String storeName;

    @JsonProperty("type")
    private String type;

    @JsonProperty("dft")
    private Object defaultValue;

    @JsonProperty("to_use")
    @JsonDeserialize(using = FlexibleBooleanDeserializer.class)
    private Boolean toUse;

    @JsonProperty("order")
    private Integer order;

    @JsonProperty("is_feedback")
    @JsonDeserialize(using = FlexibleBooleanDeserializer.class)
    private Boolean isFeedback;

    @JsonProperty("entity_scopes")
    private List<String> entityScopes = List.of();

    private final Map<String, Object> additionalProperties = new LinkedHashMap<>();

    public String name() { return name; }
    public String rawName() { return rawName; }
    public String storeName() { return storeName; }
    public String type() { return type; }
    public Object defaultValue() { return defaultValue; }
    public Boolean toUse() { return toUse; }
    public Integer order() { return order; }
    public Boolean isFeedback() { return isFeedback; }
    public List<String> entityScopes() {
        return entityScopes == null ? List.of() : List.copyOf(entityScopes);
    }
    public Map<String, Object> additionalProperties() {
        return Collections.unmodifiableMap(additionalProperties);
    }

    @JsonAnySetter
    void addAdditionalProperty(String name, Object value) {
        additionalProperties.put(name, value);
    }
}
