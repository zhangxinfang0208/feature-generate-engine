package com.example.featuredag.api;

import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.operator.OperatorDefinition;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.runtime.ObservabilityOptions;
import com.example.featuredag.runtime.RuntimeObservabilityController;
import com.example.featuredag.runtime.RuntimeObserver;
import com.example.featuredag.runtime.RuntimeTraceObserver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

public final class InitOptions {
    private final ExecutionEnvironment environment;
    private final String planId;
    private final Set<String> targetFeatures;
    private final Map<String, Set<EntityScope>> rawFeatureScopes;
    private final Set<EntityScope> defaultRawFeatureScopes;
    private final RuntimeObservabilityController observabilityController;
    private final RuntimeObserver runtimeObserver;
    private final RuntimeTraceObserver runtimeTraceObserver;
    private final BooleanSupplier requestObservationEnabled;
    private final List<OperatorDefinition> operatorExtensions;

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
        this.observabilityController = Objects.requireNonNull(
                builder.observabilityController, "observabilityController");
        this.runtimeObserver = Objects.requireNonNull(builder.runtimeObserver, "runtimeObserver");
        this.runtimeTraceObserver = Objects.requireNonNull(
                builder.runtimeTraceObserver, "runtimeTraceObserver");
        this.requestObservationEnabled = builder.requestObservationEnabled;
        this.operatorExtensions = Collections.unmodifiableList(
                new ArrayList<OperatorDefinition>(builder.operatorExtensions));
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
    public RuntimeObservabilityController observabilityController() {
        return observabilityController;
    }
    public RuntimeObserver runtimeObserver() { return runtimeObserver; }
    public RuntimeTraceObserver runtimeTraceObserver() { return runtimeTraceObserver; }
    public BooleanSupplier requestObservationEnabled() { return requestObservationEnabled; }
    public List<OperatorDefinition> operatorExtensions() { return operatorExtensions; }

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
        private RuntimeObservabilityController observabilityController =
                new RuntimeObservabilityController(ObservabilityOptions.builder().build());
        private RuntimeObserver runtimeObserver = RuntimeObserver.noop();
        private RuntimeTraceObserver runtimeTraceObserver = RuntimeTraceObserver.noop();
        private BooleanSupplier requestObservationEnabled = () -> true;
        private final List<OperatorDefinition> operatorExtensions = new ArrayList<>();

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

        /**
         * 开启包含原始特征和中间值的同步 trace；只应在本地调测时使用。
         */
        public Builder runtimeTraceObserver(RuntimeTraceObserver value) {
            this.runtimeTraceObserver = Objects.requireNonNull(value, "runtimeTraceObserver");
            return this;
        }

        /**
         * 请求级诊断/Trace 总开关。配置了观察者时，每次 generate/generateBatch 在调用线程
         * 至多求值一次，结果仅用于该次调用；分组 Batch 共用调用者的上下文。
         * 默认 true 保持旧行为；false 跳过额外诊断和 Trace，不修改共享 Controller。
         * Supplier 应只读取上下文、不阻塞；RuntimeException 按关闭处理，不影响计算。
         */
        public Builder requestObservationEnabled(BooleanSupplier value) {
            this.requestObservationEnabled = Objects.requireNonNull(value, "requestObservationEnabled");
            return this;
        }

        /** 为当前引擎创建固定初始策略；运行中热更新请传入 observabilityController。 */
        public Builder observabilityOptions(ObservabilityOptions value) {
            this.observabilityController = new RuntimeObservabilityController(
                    Objects.requireNonNull(value, "observabilityOptions"));
            return this;
        }

        public Builder observabilityController(RuntimeObservabilityController value) {
            this.observabilityController = Objects.requireNonNull(
                    value, "observabilityController");
            return this;
        }

        /**
         * 在标准清单之外注册业务算子；每个引擎实例持有独立注册表，扩展不会污染全局状态。
         */
        public Builder addOperatorExtension(OperatorDefinition value) {
            this.operatorExtensions.add(Objects.requireNonNull(value, "operatorExtension"));
            return this;
        }

        public Builder operatorExtensions(List<? extends OperatorDefinition> values) {
            this.operatorExtensions.clear();
            if (values != null) {
                for (OperatorDefinition value : values) {
                    addOperatorExtension(value);
                }
            }
            return this;
        }

        public InitOptions build() { return new InitOptions(this); }
    }
}
