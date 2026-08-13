package com.example.featuredag.config;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.FeatureDefinition;
import com.example.featuredag.definition.FeatureRole;
import com.example.featuredag.definition.OutputPolicy;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.expression.AstCall;
import com.example.featuredag.expression.AstArrayLiteral;
import com.example.featuredag.expression.AstFeatureRef;
import com.example.featuredag.expression.AstNode;
import com.example.featuredag.expression.AstObjectLiteral;
import com.example.featuredag.expression.ExpressionParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class FeatureConfigMapper {
    private FeatureConfigMapper() {}

    public static MappedFeatureSet map(
            FeatureSetConfig config,
            Set<String> requestedTargets,
            Map<String, Set<EntityScope>> scopeOverrides) {
        return map(
                config,
                requestedTargets,
                scopeOverrides,
                Set.of(EntityScope.USER));
    }

    public static MappedFeatureSet map(
            FeatureSetConfig config,
            Set<String> requestedTargets,
            Map<String, Set<EntityScope>> scopeOverrides,
            Set<EntityScope> defaultBaseScopes) {
        Objects.requireNonNull(config, "config");
        requestedTargets = requestedTargets == null ? Set.of() : requestedTargets;
        scopeOverrides = scopeOverrides == null ? Map.of() : scopeOverrides;
        defaultBaseScopes = immutableNonEmptyScopes(defaultBaseScopes, "defaultBaseScopes");

        String featureSetName = requireText(config.featureSetName(), "feature_set_name");
        String version = requireText(config.version(), "version");
        Map<String, DefinitionEntry> entries = new LinkedHashMap<>();
        List<FeatureDefinition> definitions = new ArrayList<>();
        List<DerivedEntry> enabledDerived = new ArrayList<>();
        int declarationIndex = 0;
        for (FeatureConfig feature : config.features()) {
            String name = requireText(feature.name(), "features[].name");
            DefinitionType definitionType = parseDefinitionType(feature.definitionType(), name);
            boolean enabled = isEnabled(feature.toUse());
            OutputPolicy configuredOutputPolicy = parseOutputPolicy(feature.outputPolicy(), name);
            ValueShape declaredValueShape = parseValueShape(feature.valueShape(), name);
            if (definitionType == DefinitionType.BASE
                    && feature.outputPolicy() != null && !feature.outputPolicy().isBlank()
                    && configuredOutputPolicy != OutputPolicy.OUTPUT) {
                throw new IllegalArgumentException(
                        "output_policy for BASE feature " + name + " must be OUTPUT");
            }
            OutputPolicy outputPolicy = definitionType == DefinitionType.BASE
                    ? OutputPolicy.OUTPUT : configuredOutputPolicy;
            putUnique(entries, name, new DefinitionEntry(
                    definitionType, enabled, outputPolicy, declarationIndex));

            if (definitionType == DefinitionType.BASE) {
                requireBlank(feature.expression(), "expression for BASE feature " + name);
                String sourceBinding = requireText(feature.rawName(), "raw_name for BASE feature " + name);
                Set<EntityScope> scopes = resolveScopes(name, feature.entityScopes(), scopeOverrides);
                if (scopes.isEmpty()) {
                    scopes = defaultBaseScopes;
                }
                if (enabled) {
                    DataType type = parseEnum(DataType.class, feature.type(), "type for feature " + name);
                    definitions.add(FeatureDefinition.builder()
                            .name(name)
                            .role(FeatureRole.RAW)
                            .dataType(type)
                            .entityScopes(scopes)
                            .defaultValue(convertDefault(feature.defaultValue(), type, name))
                            .sourceBinding(sourceBinding)
                            .outputPolicy(OutputPolicy.OUTPUT)
                            .declaredValueShape(resolveBaseValueShape(
                                    declaredValueShape, feature.sequenceMaxLength()))
                            .build());
                }
            } else {
                String expression = requireText(feature.expression(), "expression for DERIVED feature " + name);
                Set<EntityScope> configuredScopes = resolveScopes(name, feature.entityScopes(), Map.of());
                if (enabled) {
                    DataType type = parseEnum(DataType.class, feature.type(), "type for feature " + name);
                    FeatureDefinition definition = FeatureDefinition.builder()
                            .name(name)
                            .role(FeatureRole.DERIVED)
                            .dataType(type)
                            .entityScopes(configuredScopes)
                            .expressionContent(expression)
                            .defaultValue(convertDefault(feature.defaultValue(), type, name))
                            .outputPolicy(outputPolicy)
                            .declaredValueShape(declaredValueShape)
                            .description(feature.description())
                            .build();
                    definitions.add(definition);
                    enabledDerived.add(new DerivedEntry(feature, definition, declarationIndex));
                }
            }
            declarationIndex++;
        }

        validateDisabledReferences(entries, enabledDerived);

        Set<String> targets = selectTargets(entries, enabledDerived, requestedTargets);
        List<FeatureOutputDescriptor> outputs = enabledDerived.stream()
                .filter(entry -> targets.contains(entry.definition().name()))
                .map(FeatureConfigMapper::toOutputDescriptor)
                .sorted(outputComparator())
                .toList();
        requireUniqueStoreNames(outputs);
        LinkedHashSet<String> orderedTargets = new LinkedHashSet<>();
        for (FeatureOutputDescriptor output : outputs) orderedTargets.add(output.featureName());

        if (orderedTargets.isEmpty()) {
            throw new IllegalArgumentException("Feature config has no enabled OUTPUT derived targets");
        }
        // L0→L1：映射产物为定义集合 + 目标特征 + 输出描述，作为逻辑层构建（C3）的输入契约
        return new MappedFeatureSet(
                featureSetName,
                version,
                definitions,
                orderedTargets,
                outputs);
    }

    private static DefinitionType parseDefinitionType(String value, String featureName) {
        if (value == null || value.isBlank()) return DefinitionType.BASE;
        return parseEnum(DefinitionType.class, value,
                "definition_type for feature " + featureName);
    }

    private static OutputPolicy parseOutputPolicy(String value, String featureName) {
        if (value == null || value.isBlank()) return OutputPolicy.OUTPUT;
        return parseEnum(OutputPolicy.class, value, "output_policy for feature " + featureName);
    }

    private static ValueShape parseValueShape(String value, String featureName) {
        if (value == null || value.isBlank()) return null;
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "SCALAR" -> ValueShape.SCALAR;
            case "SEQUENCE" -> ValueShape.SEQUENCE;
            case "VECTOR" -> ValueShape.CANDIDATE_VECTOR;
            default -> throw new IllegalArgumentException(
                    "Invalid value_shape for feature " + featureName + ": " + value);
        };
    }

    private static ValueShape resolveBaseValueShape(
            ValueShape declaredValueShape, Integer sequenceMaxLength) {
        if (declaredValueShape != null) return declaredValueShape;
        return sequenceMaxLength != null && sequenceMaxLength > 1
                ? ValueShape.SEQUENCE
                : null;
    }

    private static void putUnique(
            Map<String, DefinitionEntry> entries, String name, DefinitionEntry entry) {
        if (entries.putIfAbsent(name, entry) != null) {
            throw new IllegalArgumentException("Duplicate feature definition: " + name);
        }
    }

    private static Set<String> selectTargets(
            Map<String, DefinitionEntry> entries,
            List<DerivedEntry> enabledDerived,
            Set<String> requestedTargets) {
        if (requestedTargets.isEmpty()) {
            Set<String> result = new LinkedHashSet<>();
            enabledDerived.stream()
                    .filter(entry -> entry.definition().outputPolicy() == OutputPolicy.OUTPUT)
                    .sorted(Comparator.comparing(FeatureConfigMapper::toOutputDescriptor, outputComparator()))
                    .forEach(entry -> result.add(entry.definition().name()));
            return result;
        }
        for (String requested : requestedTargets) {
            String name = requireText(requested, "target feature");
            DefinitionEntry entry = entries.get(name);
            if (entry == null) throw new IllegalArgumentException("Target feature is not defined: " + name);
            if (entry.base()) throw new IllegalArgumentException("Target feature must be derived: " + name);
            if (!entry.enabled()) throw new IllegalArgumentException("Target feature is disabled: " + name);
            if (entry.outputPolicy() != OutputPolicy.OUTPUT) {
                throw new IllegalArgumentException("Target feature is INTERNAL_ONLY: " + name);
            }
        }
        return new LinkedHashSet<>(requestedTargets);
    }

    private static void validateDisabledReferences(
            Map<String, DefinitionEntry> entries,
            List<DerivedEntry> enabledDerived) {
        ExpressionParser parser = new ExpressionParser();
        for (DerivedEntry entry : enabledDerived) {
            Set<String> references = new LinkedHashSet<>();
            collectFeatureReferences(
                    parser.parse(entry.definition().expressionContent()), references);
            for (String reference : references) {
                DefinitionEntry referenced = entries.get(reference);
                if (referenced != null && !referenced.enabled()) {
                    throw new IllegalArgumentException(
                            "Referenced feature is disabled: " + reference
                                    + " (from " + entry.definition().name() + ")");
                }
            }
        }
    }

    private static void collectFeatureReferences(AstNode node, Set<String> references) {
        if (node instanceof AstFeatureRef featureRef) {
            references.add(featureRef.featureName());
        } else if (node instanceof AstCall call) {
            for (AstNode argument : call.arguments()) {
                collectFeatureReferences(argument, references);
            }
        } else if (node instanceof AstArrayLiteral arrayLiteral) {
            for (AstNode element : arrayLiteral.elements()) {
                collectFeatureReferences(element, references);
            }
        } else if (node instanceof AstObjectLiteral objectLiteral) {
            for (AstNode field : objectLiteral.fields().values()) {
                collectFeatureReferences(field, references);
            }
        }
    }

    private static FeatureOutputDescriptor toOutputDescriptor(DerivedEntry entry) {
        FeatureConfig config = entry.config();
        String name = entry.definition().name();
        String storeName = config.storeName() == null || config.storeName().isBlank()
                ? name : config.storeName().trim();
        int order = config.order() == null ? Integer.MAX_VALUE : config.order();
        return new FeatureOutputDescriptor(name, storeName, order, entry.declarationIndex());
    }

    private static Comparator<FeatureOutputDescriptor> outputComparator() {
        return Comparator.comparingInt(FeatureOutputDescriptor::order)
                .thenComparingInt(FeatureOutputDescriptor::declarationIndex);
    }

    private static void requireUniqueStoreNames(List<FeatureOutputDescriptor> outputs) {
        Set<String> names = new LinkedHashSet<>();
        for (FeatureOutputDescriptor output : outputs) {
            if (!names.add(output.storeName())) {
                throw new IllegalArgumentException("Duplicate output store_name: " + output.storeName());
            }
        }
    }

    private static Set<EntityScope> resolveScopes(
            String featureName,
            List<String> configuredScopes,
            Map<String, Set<EntityScope>> overrides) {
        Set<EntityScope> result = new LinkedHashSet<>();
        for (String scope : configuredScopes) {
            result.add(parseEnum(EntityScope.class, scope, "entity scope for feature " + featureName));
        }
        Set<EntityScope> override = overrides.get(featureName);
        if (override != null) {
            if (override.isEmpty()) return Set.of();
            return Collections.unmodifiableSet(new LinkedHashSet<>(override));
        }
        return Collections.unmodifiableSet(result);
    }

    private static Set<EntityScope> immutableNonEmptyScopes(
            Set<EntityScope> scopes, String field) {
        Objects.requireNonNull(scopes, field);
        LinkedHashSet<EntityScope> result = new LinkedHashSet<>();
        for (EntityScope scope : scopes) {
            result.add(Objects.requireNonNull(scope, field + " must not contain null"));
        }
        if (result.isEmpty()) throw new IllegalArgumentException(field + " must not be empty");
        return Collections.unmodifiableSet(result);
    }

    private static Object convertDefault(Object value, DataType type, String featureName) {
        if (value == null || type == DataType.UNKNOWN) return value;
        return switch (type) {
            case STRING -> {
                if (!(value instanceof String)) throw invalidDefault(featureName, type, value);
                yield value;
            }
            case INT -> {
                if (!(value instanceof Number number)) throw invalidDefault(featureName, type, value);
                double doubleValue = number.doubleValue();
                long longValue = number.longValue();
                if (doubleValue != longValue || longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
                    throw invalidDefault(featureName, type, value);
                }
                yield (int) longValue;
            }
            case DOUBLE -> {
                if (!(value instanceof Number number)) throw invalidDefault(featureName, type, value);
                yield number.doubleValue();
            }
            case BOOLEAN -> {
                if (!(value instanceof Boolean)) throw invalidDefault(featureName, type, value);
                yield value;
            }
            case OBJECT -> {
                if (!(value instanceof Map<?, ?>)) throw invalidDefault(featureName, type, value);
                yield value;
            }
            case EVENT_SEQUENCE -> throw invalidDefault(featureName, type, value);
            case UNKNOWN -> value;
        };
    }

    private static IllegalArgumentException invalidDefault(
            String featureName, DataType type, Object value) {
        return new IllegalArgumentException(
                "Default value for feature " + featureName + " is not compatible with " + type + ": " + value);
    }

    private static boolean isEnabled(Boolean value) {
        return value == null || value;
    }

    private static void requireBlank(String value, String field) {
        if (value != null && !value.isBlank()) {
            throw new IllegalArgumentException(field + " must be blank");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static <E extends Enum<E>> E parseEnum(
            Class<E> type, String value, String field) {
        String text = requireText(value, field);
        try {
            return Enum.valueOf(type, text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid " + field + ": " + value, error);
        }
    }

    private record DefinitionEntry(
            DefinitionType definitionType,
            boolean enabled,
            OutputPolicy outputPolicy,
            int declarationIndex) {
        boolean base() { return definitionType == DefinitionType.BASE; }
    }

    private record DerivedEntry(
            FeatureConfig config,
            FeatureDefinition definition,
            int declarationIndex) {}
}
