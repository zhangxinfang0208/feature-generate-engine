package com.example.featuredag.config;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DerivedFeatureConfig {
    @JsonProperty("name")
    private String name;

    @JsonProperty("store_name")
    private String storeName;

    @JsonProperty("type")
    private String type;

    @JsonProperty("expression")
    private String expression;

    @JsonProperty("to_use")
    @JsonDeserialize(using = FlexibleBooleanDeserializer.class)
    private Boolean toUse;

    @JsonProperty("output_policy")
    private String outputPolicy;

    @JsonProperty("dft")
    private Object defaultValue;

    @JsonProperty("order")
    private Integer order;

    @JsonProperty("description")
    private String description;

    private final Map<String, Object> additionalProperties = new LinkedHashMap<>();

    public String name() { return name; }
    public String storeName() { return storeName; }
    public String type() { return type; }
    public String expression() { return expression; }
    public Boolean toUse() { return toUse; }
    public String outputPolicy() { return outputPolicy; }
    public Object defaultValue() { return defaultValue; }
    public Integer order() { return order; }
    public String description() { return description; }
    public Map<String, Object> additionalProperties() {
        return Collections.unmodifiableMap(additionalProperties);
    }

    @JsonAnySetter
    void addAdditionalProperty(String name, Object value) {
        additionalProperties.put(name, value);
    }
}
