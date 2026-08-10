package com.example.featuredag.api;

import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.runtime.RuntimeObserver;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class InitOptions {
    private final ExecutionEnvironment environment;
    private final String planId;
    private final Set<String> targetFeatures;
    private final Map<String, Set<EntityScope>> rawFeatureScopes;
    private final Set<EntityScope> defaultRawFeatureScopes;
    private final RuntimeObserver runtimeObserver;

    private InitOptions(Builder builder) {
        this.environment = Objects.requireNonNull(builder.environment, "environment");
        this.planId = blankToNull(builder.planId);
        this.targetFeatures = Collections.unmodifiableSet(
                new LinkedHashSet<>(builder.targetFeatures));
        Map<String, Set<EntityScope>> scopes = new LinkedHashMap<>();
        for (Map.Entry<String, Set<EntityScope>> entry : builder.rawFeatureScopes.entrySet()) {
            String name = requireText(entry.getKey(), "raw feature scope name");
            scopes.put(name, Collections.unmodifiableSet(
                    new LinkedHashSet<>(Objects.requireNonNull(entry.getValue(), "scope set"))));
        }
        this.rawFeatureScopes = Collections.unmodifiableMap(scopes);
        this.defaultRawFeatureScopes = immutableNonEmptyScopes(
                builder.defaultRawFeatureScopes, "default raw feature scopes");
        this.runtimeObserver = Objects.requireNonNull(builder.runtimeObserver, "runtimeObserver");
    }

    public static Builder builder() { return new Builder(); }

    public static InitOptions offline(String planId) {
        return builder().environment(ExecutionEnvironment.OFFLINE).planId(planId).build();
    }

    public static InitOptions online(String planId) {
        return builder().environment(ExecutionEnvironment.ONLINE).planId(planId).build();
    }

    public ExecutionEnvironment environment() { return environment; }
    public String planId() { return planId; }
    public Set<String> targetFeatures() { return targetFeatures; }
    public Map<String, Set<EntityScope>> rawFeatureScopes() { return rawFeatureScopes; }
    public Set<EntityScope> defaultRawFeatureScopes() { return defaultRawFeatureScopes; }
    public RuntimeObserver runtimeObserver() { return runtimeObserver; }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }

    private static String requireText(String value, String field) {
        String result = blankToNull(value);
        if (result == null) throw new IllegalArgumentException(field + " must not be blank");
        return result;
    }

    private static Set<EntityScope> immutableNonEmptyScopes(
            Set<EntityScope> values, String field) {
        Objects.requireNonNull(values, field);
        LinkedHashSet<EntityScope> result = new LinkedHashSet<>();
        for (EntityScope value : values) {
            result.add(Objects.requireNonNull(value, field + " must not contain null"));
        }
        if (result.isEmpty()) throw new IllegalArgumentException(field + " must not be empty");
        return Collections.unmodifiableSet(result);
    }

    public static final class Builder {
        private ExecutionEnvironment environment;
        private String planId;
        private final Set<String> targetFeatures = new LinkedHashSet<>();
        private final Map<String, Set<EntityScope>> rawFeatureScopes = new LinkedHashMap<>();
        private final Set<EntityScope> defaultRawFeatureScopes = new LinkedHashSet<>(
                Set.of(EntityScope.USER));
        private RuntimeObserver runtimeObserver = RuntimeObserver.noop();

        public Builder environment(ExecutionEnvironment value) {
            this.environment = value;
            return this;
        }

        public Builder planId(String value) {
            this.planId = value;
            return this;
        }

        public Builder targetFeatures(Set<String> values) {
            this.targetFeatures.clear();
            if (values != null) this.targetFeatures.addAll(values);
            return this;
        }

        public Builder rawFeatureScopes(Map<String, Set<EntityScope>> values) {
            this.rawFeatureScopes.clear();
            if (values != null) this.rawFeatureScopes.putAll(values);
            return this;
        }

        public Builder defaultRawFeatureScopes(Set<EntityScope> values) {
            this.defaultRawFeatureScopes.clear();
            if (values != null) this.defaultRawFeatureScopes.addAll(values);
            return this;
        }

        public Builder runtimeObserver(RuntimeObserver value) {
            this.runtimeObserver = Objects.requireNonNull(value, "runtimeObserver");
            return this;
        }

        public InitOptions build() { return new InitOptions(this); }
    }
}
