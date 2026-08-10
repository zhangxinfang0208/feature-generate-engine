package com.example.featuredag.definition;

import com.example.featuredag.logical.ValueShape;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * L0 data model. Raw features must declare entityScopes. Derived features obtain
 * their final scope from logical DAG inference.
 *
 * 定义层（L0）：特征是三层构建的输入契约，构造后不可变（C2）。
 * RAW 特征必须声明实体域且禁止携带表达式；DERIVED 特征必须携带表达式，
 * 其最终实体域由逻辑层推断，而非在此声明。
 */
public final class FeatureDefinition {
    private final String name;
    private final FeatureRole role;
    private final DataType dataType;
    private final Set<EntityScope> entityScopes;
    private final String expressionContent;
    private final Object defaultValue;
    private final String sourceBinding;
    private final OutputPolicy outputPolicy;
    private final ValueShape declaredValueShape;
    private final String description;

    private FeatureDefinition(Builder builder) {
        this.name = requireText(builder.name, "name");
        this.role = Objects.requireNonNull(builder.role, "role");
        this.dataType = Objects.requireNonNull(builder.dataType, "dataType");
        this.entityScopes = Collections.unmodifiableSet(new LinkedHashSet<>(builder.entityScopes));
        this.expressionContent = blankToNull(builder.expressionContent);
        this.defaultValue = builder.defaultValue;
        this.sourceBinding = blankToNull(builder.sourceBinding);
        this.outputPolicy = Objects.requireNonNull(builder.outputPolicy, "outputPolicy");
        this.declaredValueShape = builder.declaredValueShape;
        this.description = blankToNull(builder.description);
        validate();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static FeatureDefinition raw(
            String name,
            DataType dataType,
            EntityScope scope,
            Object defaultValue) {
        return builder()
                .name(name)
                .role(FeatureRole.RAW)
                .dataType(dataType)
                .addEntityScope(scope)
                .defaultValue(defaultValue)
                .sourceBinding(name)
                .outputPolicy(OutputPolicy.OUTPUT)
                .build();
    }

    public static FeatureDefinition derived(
            String name,
            DataType dataType,
            String expressionContent,
            OutputPolicy outputPolicy) {
        return builder()
                .name(name)
                .role(FeatureRole.DERIVED)
                .dataType(dataType)
                .expressionContent(expressionContent)
                .outputPolicy(outputPolicy)
                .build();
    }

    /**
     * 约束 C2：RAW 必须声明实体域且不能有表达式；DERIVED 必须有表达式。
     * 校验失败立即抛异常，不产出半成品定义。
     */
    private void validate() {
        if (role == FeatureRole.RAW) {
            if (entityScopes.isEmpty()) {
                throw new IllegalArgumentException("Raw feature must declare entityScopes: " + name);
            }
            if (expressionContent != null) {
                throw new IllegalArgumentException("Raw feature cannot have expression: " + name);
            }
        } else if (expressionContent == null) {
            throw new IllegalArgumentException("Derived feature must have expression: " + name);
        }
    }

    public String name() { return name; }
    public FeatureRole role() { return role; }
    public DataType dataType() { return dataType; }
    public Set<EntityScope> entityScopes() { return entityScopes; }
    public String expressionContent() { return expressionContent; }
    public Object defaultValue() { return defaultValue; }
    public String sourceBinding() { return sourceBinding; }
    public OutputPolicy outputPolicy() { return outputPolicy; }
    public ValueShape declaredValueShape() { return declaredValueShape; }
    public String description() { return description; }
    public boolean isRaw() { return role == FeatureRole.RAW; }

    private static String requireText(String value, String field) {
        String result = blankToNull(value);
        if (result == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return result;
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static final class Builder {
        private String name;
        private FeatureRole role = FeatureRole.DERIVED;
        private DataType dataType = DataType.UNKNOWN;
        private final Set<EntityScope> entityScopes = new LinkedHashSet<>();
        private String expressionContent;
        private Object defaultValue;
        private String sourceBinding;
        private OutputPolicy outputPolicy = OutputPolicy.OUTPUT;
        private ValueShape declaredValueShape;
        private String description;

        public Builder name(String value) { this.name = value; return this; }
        public Builder role(FeatureRole value) { this.role = value; return this; }
        public Builder dataType(DataType value) { this.dataType = value; return this; }
        public Builder entityScopes(Set<EntityScope> value) {
            this.entityScopes.clear();
            if (value != null) this.entityScopes.addAll(value);
            return this;
        }
        public Builder addEntityScope(EntityScope value) {
            this.entityScopes.add(Objects.requireNonNull(value));
            return this;
        }
        public Builder expressionContent(String value) { this.expressionContent = value; return this; }
        public Builder defaultValue(Object value) { this.defaultValue = value; return this; }
        public Builder sourceBinding(String value) { this.sourceBinding = value; return this; }
        public Builder outputPolicy(OutputPolicy value) { this.outputPolicy = value; return this; }
        public Builder declaredValueShape(ValueShape value) { this.declaredValueShape = value; return this; }
        public Builder description(String value) { this.description = value; return this; }
        public FeatureDefinition build() { return new FeatureDefinition(this); }
    }
}
