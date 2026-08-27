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

import java.math.BigDecimal;
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

/**
 * 配置层适配器：把面向使用方的宽松配置模型收敛为引擎内部的严格定义模型。
 *
 * <p>本类负责默认值、枚举、开关、实体域覆盖和输出顺序等配置语义；表达式本身只在这里
 * 做引用完整性预检，真正的类型/形状推断仍由逻辑层完成，从而保持 C1 的单向分层依赖。</p>
 */
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
        // 第一阶段：统一可选参数和集合默认值，后续代码只处理非 null 的规范化输入。
        Objects.requireNonNull(config, "config");
        requestedTargets = requestedTargets == null ? Set.of() : requestedTargets;
        scopeOverrides = scopeOverrides == null ? Map.of() : scopeOverrides;
        defaultBaseScopes = immutableNonEmptyScopes(defaultBaseScopes, "defaultBaseScopes");

        String featureSetName = requireText(config.featureSetName(), "feature_set_name");
        String version = requireText(config.version(), "version");
        Map<String, DefinitionEntry> entries = new LinkedHashMap<>();
        Set<String> disabledFeatureNames = new LinkedHashSet<>();
        List<FeatureDefinition> definitions = new ArrayList<>();
        List<DerivedEntry> enabledDerived = new ArrayList<>();
        int declarationIndex = 0;
        // 第二阶段：逐条配置转换为不可变 FeatureDefinition；禁用项在严格字段
        // 校验前过滤，只保留非空名称用于禁用引用和显式目标诊断。
        for (FeatureConfig feature : config.features()) {
            boolean enabled = isEnabled(feature.toUse());
            if (!enabled) {
                if (hasText(feature.name())) {
                    putDisabledFeature(
                            entries, disabledFeatureNames, feature.name().trim());
                }
                declarationIndex++;
                continue;
            }
            boolean explicitDefinition = hasText(feature.definitionType());
            // 未显式进入 DAG 定义协议的历史元数据可能无名且不可达；直接略过，
            // 避免对模型未使用的宽表字段施加定义层必填约束。
            if (!explicitDefinition && !hasText(feature.name())) {
                declarationIndex++;
                continue;
            }
            String name = requireText(feature.name(), "features[].name");
            DefinitionType definitionType = parseDefinitionType(feature.definitionType(), name);
            OutputPolicy configuredOutputPolicy = parseOutputPolicy(feature.outputPolicy(), name);
            ValueShape declaredValueShape = parseValueShape(feature.valueShape(), name);
            Integer sequenceMaxLength = validateSequenceMaxLength(
                    feature.sequenceMaxLength(), name);
            DataType configuredType = parseFeatureType(
                    feature.type(), name, explicitDefinition);
            if (explicitDefinition
                    && definitionType == DefinitionType.BASE
                    && feature.outputPolicy() != null && !feature.outputPolicy().isBlank()
                    && configuredOutputPolicy != OutputPolicy.OUTPUT) {
                throw new IllegalArgumentException(
                        "output_policy for BASE feature " + name + " must be OUTPUT");
            }
            OutputPolicy outputPolicy = definitionType == DefinitionType.BASE
                    ? OutputPolicy.OUTPUT : configuredOutputPolicy;
            putUnique(entries, disabledFeatureNames, name, new DefinitionEntry(
                    definitionType, outputPolicy));

            if (definitionType == DefinitionType.BASE) {
                // C2：显式 BASE 对应严格 RAW 定义，禁止表达式；未显式声明的历史
                // 条目按宽松 BASE 兼容，缺失类型使用 UNKNOWN，便于不可达特征在 C3 裁剪。
                if (explicitDefinition) {
                    requireBlank(feature.expression(), "expression for BASE feature " + name);
                }
                // 模型特征以 name 作为唯一身份；raw_name 仅作为历史配置字段保留，
                // 不再影响请求输入键或后续 DAG 节点的 sourceBinding。
                String sourceBinding = name;
                Set<EntityScope> scopes = resolveScopes(name, feature.entityScopes(), scopeOverrides);
                if (scopes.isEmpty()) {
                    scopes = defaultBaseScopes;
                }
                FeatureDefinition.Builder builder = FeatureDefinition.builder()
                        .name(name)
                        .role(FeatureRole.RAW)
                        .dataType(configuredType)
                        .entityScopes(scopes)
                        .sourceBinding(sourceBinding)
                        .outputPolicy(OutputPolicy.OUTPUT)
                        .declaredValueShape(resolveBaseValueShape(
                                declaredValueShape, sequenceMaxLength));
                // dft 缺失或显式为 null 都表示没有非空默认值，保持公共配置契约。
                if (feature.defaultValue() != null) {
                    builder.defaultValue(convertDefault(
                            feature.defaultValue(), configuredType, name));
                }
                definitions.add(builder.build());
            } else {
                // C2：DERIVED 必须携带表达式；声明形状和实体域将在逻辑推断后按 C6 再次核对。
                String expression = requireText(feature.expression(), "expression for DERIVED feature " + name);
                Set<EntityScope> configuredScopes = resolveScopes(name, feature.entityScopes(), Map.of());
                FeatureDefinition.Builder builder = FeatureDefinition.builder()
                        .name(name)
                        .role(FeatureRole.DERIVED)
                        .dataType(configuredType)
                        .entityScopes(configuredScopes)
                        .expressionContent(expression)
                        .outputPolicy(outputPolicy)
                        .declaredValueShape(declaredValueShape)
                        .description(feature.description());
                // dft 缺失或显式为 null 都表示没有非空默认值，保持公共配置契约。
                if (feature.defaultValue() != null) {
                    builder.defaultValue(convertDefault(
                            feature.defaultValue(), configuredType, name));
                }
                FeatureDefinition definition = builder.build();
                definitions.add(definition);
                enabledDerived.add(new DerivedEntry(feature, definition, declarationIndex));
            }
            declarationIndex++;
        }

        // 在目标裁剪前检查所有启用派生特征，避免禁用依赖因“暂未选为目标”而潜伏到后续初始化。
        validateDisabledReferences(disabledFeatureNames, enabledDerived);

        // 第三阶段：确定真正需要构图的根，并生成稳定排序的外部输出描述。
        Set<String> targets = selectTargets(
                entries, disabledFeatureNames, enabledDerived, requestedTargets);
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

    private static DataType parseFeatureType(
            String value, String featureName, boolean explicitDefinition) {
        if (!hasText(value) && !explicitDefinition) return DataType.UNKNOWN;
        return parseEnum(DataType.class, value, "type for feature " + featureName);
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

    private static Integer validateSequenceMaxLength(Integer value, String featureName) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(
                    "seq_max_length for feature " + featureName + " must be positive");
        }
        return value;
    }

    private static void putUnique(
            Map<String, DefinitionEntry> entries,
            Set<String> disabledFeatureNames,
            String name,
            DefinitionEntry entry) {
        if (disabledFeatureNames.contains(name)
                || entries.putIfAbsent(name, entry) != null) {
            throw new IllegalArgumentException("Duplicate feature definition: " + name);
        }
    }

    private static void putDisabledFeature(
            Map<String, DefinitionEntry> entries,
            Set<String> disabledFeatureNames,
            String name) {
        if (entries.containsKey(name) || !disabledFeatureNames.add(name)) {
            throw new IllegalArgumentException("Duplicate feature definition: " + name);
        }
    }

    private static Set<String> selectTargets(
            Map<String, DefinitionEntry> entries,
            Set<String> disabledFeatureNames,
            List<DerivedEntry> enabledDerived,
            Set<String> requestedTargets) {
        // 未显式指定时，以所有启用且允许输出的派生特征作为根；INTERNAL_ONLY 只能作为中间依赖。
        if (requestedTargets.isEmpty()) {
            Set<String> result = new LinkedHashSet<>();
            enabledDerived.stream()
                    .filter(entry -> entry.definition().outputPolicy() == OutputPolicy.OUTPUT)
                    .sorted(Comparator.comparing(FeatureConfigMapper::toOutputDescriptor, outputComparator()))
                    .forEach(entry -> result.add(entry.definition().name()));
            return result;
        }
        // 显式目标必须同时满足“存在、派生、启用、可输出”，尽量在构图前给出配置级错误。
        for (String requested : requestedTargets) {
            String name = requireText(requested, "target feature");
            if (disabledFeatureNames.contains(name)) {
                throw new IllegalArgumentException("Target feature is disabled: " + name);
            }
            DefinitionEntry entry = entries.get(name);
            if (entry == null) throw new IllegalArgumentException("Target feature is not defined: " + name);
            if (entry.base()) throw new IllegalArgumentException("Target feature must be derived: " + name);
            if (entry.outputPolicy() != OutputPolicy.OUTPUT) {
                throw new IllegalArgumentException("Target feature is INTERNAL_ONLY: " + name);
            }
        }
        return new LinkedHashSet<>(requestedTargets);
    }

    private static void validateDisabledReferences(
            Set<String> disabledFeatureNames,
            List<DerivedEntry> enabledDerived) {
        ExpressionParser parser = new ExpressionParser();
        for (DerivedEntry entry : enabledDerived) {
            // 此处只遍历 AST 收集引用，不生成或持久化逻辑节点；AST 生命周期仍止于构建阶段（C3）。
            Set<String> references = new LinkedHashSet<>();
            collectFeatureReferences(
                    parser.parse(entry.definition().expressionContent()), references);
            for (String reference : references) {
                if (disabledFeatureNames.contains(reference)) {
                    throw new IllegalArgumentException(
                            "Referenced feature is disabled: " + reference
                                    + " (from " + entry.definition().name() + ")");
                }
            }
        }
    }

    private static void collectFeatureReferences(AstNode node, Set<String> references) {
        // 数组和对象字面量也可能嵌套特征引用，因此需要递归覆盖全部复合 AST 分支。
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
        // store_name 缺省时沿用特征名；未配置 order 的输出排在显式顺序之后。
        String storeName = config.storeName() == null || config.storeName().isBlank()
                ? name : config.storeName().trim();
        int order = config.order() == null ? Integer.MAX_VALUE : config.order();
        return new FeatureOutputDescriptor(
                name,
                storeName,
                order,
                entry.declarationIndex(),
                config.sequenceMaxLength());
    }

    private static Comparator<FeatureOutputDescriptor> outputComparator() {
        // declarationIndex 作为稳定次序，保证相同 order 的配置在多次初始化中输出顺序一致。
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
            // 覆盖项存在即完全替换配置值；显式空集合留给调用方表示“未声明”，再由上层应用默认域。
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
        // 默认值在配置边界完成类型收窄，运行时命中默认分支时无需再次猜测或转换类型。
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
                // 同时拒绝小数和超出 int 范围的整数，避免静默截断改变特征语义。
                if (doubleValue != longValue || longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
                    throw invalidDefault(featureName, type, value);
                }
                yield (int) longValue;
            }
            case BIGINT -> {
                if (!(value instanceof Number number)) throw invalidDefault(featureName, type, value);
                try {
                    // 配置默认值必须是 long 范围内的精确整数，禁止小数截断或溢出回绕。
                    yield Long.valueOf(new BigDecimal(number.toString()).longValueExact());
                } catch (ArithmeticException | NumberFormatException error) {
                    throw invalidDefault(featureName, type, value);
                }
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

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
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
            OutputPolicy outputPolicy) {
        boolean base() { return definitionType == DefinitionType.BASE; }
    }

    private record DerivedEntry(
            FeatureConfig config,
            FeatureDefinition definition,
            int declarationIndex) {}
}
