package com.example.featuredag.operator;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.logical.LogicalNode;
import com.example.featuredag.logical.ValueShape;
import com.example.featuredag.runtime.SequenceBlock;
import com.example.featuredag.runtime.SequenceValue;
import com.example.featuredag.runtime.SequenceView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Registry shared by logical inference and runtime execution. */
public final class OperatorRegistry {
    private final Map<String, OperatorDefinition> definitions = new LinkedHashMap<>();

    public OperatorRegistry register(OperatorDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        OperatorDefinition previous = definitions.putIfAbsent(definition.name(), definition);
        if (previous != null) {
            throw new IllegalArgumentException("Operator already registered: " + definition.name());
        }
        return this;
    }

    public OperatorDefinition require(String name) {
        OperatorDefinition definition = definitions.get(name);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown operator: " + name);
        }
        return definition;
    }

    public OperatorInference infer(String name, List<LogicalNode> inputs) {
        OperatorDefinition definition = require(name);
        validateArity(definition, inputs.size());
        return definition.infer(inputs);
    }

    public Object evaluate(String name, List<Object> arguments) {
        OperatorDefinition definition = require(name);
        validateArity(definition, arguments.size());
        return definition.evaluate(arguments);
    }

    public static OperatorRegistry standard() {
        OperatorRegistry registry = new OperatorRegistry();
        registry.register(simple("coalesce", 1, Integer.MAX_VALUE, true, false, true,
                inputs -> new OperatorInference(
                        inputs.get(0).outputType(), unionScopes(inputs), inputs.get(0).valueShape()),
                args -> {
                    for (Object arg : args) if (arg != null) return arg;
                    return null;
                }));

        registry.register(simple("normalize", 2, 2, true, true, false,
                inputs -> new OperatorInference(DataType.DOUBLE, unionScopes(inputs), ValueShape.SCALAR),
                args -> {
                    if (args.get(0) == null) return null;
                    double value = asNumber(args.get(0)).doubleValue();
                    Map<?, ?> params = asMap(args.get(1));
                    double min = getDouble(params, "min", 0.0);
                    double max = getDouble(params, "max", 1.0);
                    if (max == min) return 0.0;
                    return (value - min) / (max - min);
                }));

        registry.register(simple("extractIndustry", 2, 2, true, false, true,
                inputs -> new OperatorInference(DataType.EVENT_SEQUENCE, unionScopes(inputs), ValueShape.SEQUENCE),
                args -> {
                    SequenceValue sequence = asSequence(args.get(0));
                    String industry = String.valueOf(args.get(1));
                    return SequenceView.filterByIndustry(sequence, industry);
                }));

        registry.register(simple("count", 1, 1, true, false, true,
                inputs -> {
                    LogicalNode input = inputs.getFirst();
                    if (input.valueShape() != ValueShape.SEQUENCE) {
                        throw new IllegalArgumentException(
                                "count expects a sequence/collection input, got type="
                                        + input.outputType() + ", shape=" + input.valueShape()
                                        + " from " + input.sourceFeatureName());
                    }
                    return new OperatorInference(DataType.INT, unionScopes(inputs), ValueShape.SCALAR);
                },
                args -> {
                    Object value = args.get(0);
                    if (value == null) return 0;
                    if (value instanceof SequenceValue sequence) return sequence.size();
                    if (value instanceof Collection<?> collection) return collection.size();
                    if (value.getClass().isArray()) return java.lang.reflect.Array.getLength(value);
                    throw new IllegalArgumentException("count does not support: " + value.getClass());
                }));

        registry.register(simple("add", 2, 2, true, false, false,
                inputs -> new OperatorInference(DataType.DOUBLE, unionScopes(inputs), ValueShape.SCALAR),
                args -> asNumber(args.get(0)).doubleValue() + asNumber(args.get(1)).doubleValue()));

        registry.register(simple("log", 1, 1, true, false, false,
                inputs -> new OperatorInference(DataType.DOUBLE, unionScopes(inputs), ValueShape.SCALAR),
                args -> Math.log(asNumber(args.get(0)).doubleValue())));

        registry.register(simple("multiply", 2, 2, true, false, false,
                inputs -> new OperatorInference(DataType.DOUBLE, unionScopes(inputs), ValueShape.SCALAR),
                args -> asNumber(args.get(0)).doubleValue() * asNumber(args.get(1)).doubleValue()));

        return registry;
    }

    private static OperatorDefinition simple(
            String name,
            int minArgs,
            int maxArgs,
            boolean deterministic,
            boolean parameterized,
            boolean supportsView,
            java.util.function.Function<List<LogicalNode>, OperatorInference> inference,
            java.util.function.Function<List<Object>, Object> evaluator) {
        return new OperatorDefinition() {
            @Override public String name() { return name; }
            @Override public int minArguments() { return minArgs; }
            @Override public int maxArguments() { return maxArgs; }
            @Override public boolean deterministic() { return deterministic; }
            @Override public boolean parameterized() { return parameterized; }
            @Override public boolean supportsSequenceView() { return supportsView; }
            @Override public OperatorInference infer(List<LogicalNode> inputs) { return inference.apply(inputs); }
            @Override public Object evaluate(List<Object> arguments) { return evaluator.apply(arguments); }
        };
    }

    private static Set<EntityScope> unionScopes(List<LogicalNode> inputs) {
        Set<EntityScope> result = new LinkedHashSet<>();
        for (LogicalNode input : inputs) result.addAll(input.entityScopes());
        return result;
    }

    private static void validateArity(OperatorDefinition definition, int count) {
        if (count < definition.minArguments() || count > definition.maxArguments()) {
            throw new IllegalArgumentException(
                    "Operator " + definition.name() + " expects " + definition.minArguments()
                            + ".." + definition.maxArguments() + " arguments, got " + count);
        }
    }

    private static Number asNumber(Object value) {
        if (value instanceof Number number) return number;
        throw new IllegalArgumentException("Expected numeric value, got: " + value);
    }

    private static Map<?, ?> asMap(Object value) {
        if (value instanceof Map<?, ?> map) return map;
        throw new IllegalArgumentException("Expected object/map, got: " + value);
    }

    private static SequenceValue asSequence(Object value) {
        if (value instanceof SequenceValue sequence) return sequence;
        throw new IllegalArgumentException("Expected SequenceValue, got: " + value);
    }

    private static double getDouble(Map<?, ?> params, String key, double defaultValue) {
        Object value = params.get(key);
        return value == null ? defaultValue : asNumber(value).doubleValue();
    }
}
