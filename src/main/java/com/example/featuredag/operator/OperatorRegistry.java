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

        registry.register(simple("add", 2, Integer.MAX_VALUE, true, false, false,
                inputs -> new OperatorInference(DataType.DOUBLE, unionScopes(inputs), ValueShape.SCALAR),
                args -> {
                    double result = 0.0;
                    for (Object arg : args) result += asNumber(arg).doubleValue();
                    return result;
                }));

        registry.register(simple("log", 1, 1, true, false, false,
                inputs -> new OperatorInference(DataType.DOUBLE, unionScopes(inputs), ValueShape.SCALAR),
                args -> Math.log(asNumber(args.get(0)).doubleValue())));

        registry.register(simple("multiply", 2, 2, true, false, false,
                inputs -> new OperatorInference(DataType.DOUBLE, unionScopes(inputs), ValueShape.SCALAR),
                args -> asNumber(args.get(0)).doubleValue() * asNumber(args.get(1)).doubleValue()));

        registerSequenceOperators(registry);
        registerConversionOperators(registry);
        registerScalarOperators(registry);
        registerOpsListOperators(registry);
        return registry;
    }

    private static void registerSequenceOperators(OperatorRegistry registry) {
        registry.register(simple("find_list_index_typed", 2, 2, true, false, true,
                fixed(DataType.INT, ValueShape.SEQUENCE), OperatorRegistry::evaluateFindListIndexTyped));
        registry.register(simple("list_index_typed", 2, 2, true, false, true,
                passThrough(0), OperatorRegistry::evaluateListIndexTyped));
        registry.register(simple("greater_in_sequence_typed", 3, 3, true, true, true,
                fixed(DataType.INT, ValueShape.SEQUENCE),
                OperatorRegistry::evaluateGreaterInSequenceTyped));
        registry.register(simple("greater_than_index_typed", 3, 3, true, true, true,
                fixed(DataType.INT, ValueShape.SEQUENCE), unsupported("greater_than_index_typed")));
        registry.register(simple("reverse_typed", 1, 1, true, false, true,
                passThrough(0), unsupported("reverse_typed")));
        registry.register(simple("slice_v3_typed", 2, 2, true, true, true,
                passThrough(1), unsupported("slice_v3_typed")));
        registry.register(simple("intersection_typed", 2, 2, true, false, true,
                passThrough(0), unsupported("intersection_typed")));
        registry.register(simple("uniq_key_index", 1, 1, true, false, true,
                fixed(DataType.INT, ValueShape.SEQUENCE), unsupported("uniq_key_index")));
        registry.register(simple("list_2_map", 2, 2, true, false, false,
                fixed(DataType.OBJECT, ValueShape.OBJECT), unsupported("list_2_map")));
        registry.register(simple("thf_default_", 2, 2, true, false, true,
                passThrough(1), unsupported("thf_default_")));
    }

    private static void registerConversionOperators(OperatorRegistry registry) {
        registry.register(simple("64", 1, 1, true, false, true,
                passThrough(0), unsupported("64")));
        registry.register(simple("value2key", 1, 1, true, false, true,
                passThrough(0), unsupported("value2key")));
        registry.register(simple("k2v", 1, 1, true, false, true,
                passThrough(0), unsupported("k2v")));
        registry.register(simple("k2v_f", 1, 1, true, false, true,
                fixed(DataType.DOUBLE, ValueShape.SEQUENCE), unsupported("k2v_f")));
        registry.register(simple("v2v", 1, 1, true, false, true,
                passThrough(0), unsupported("v2v")));
        registry.register(simple("multi_v2", 1, 1, true, false, true,
                passThrough(0), unsupported("multi_v2")));
    }

    private static void registerScalarOperators(OperatorRegistry registry) {
        registry.register(simple("sub", 2, 2, true, false, false,
                fixed(DataType.DOUBLE, ValueShape.SCALAR),
                args -> asNumber(args.get(0)).doubleValue()
                        - asNumber(args.get(1)).doubleValue()));
        registry.register(simple("sign", 1, 1, true, false, false,
                fixed(DataType.INT, ValueShape.SCALAR),
                args -> {
                    double value = asNumber(args.getFirst()).doubleValue();
                    if (value < 0.0) return -1;
                    if (value > 0.0) return 1;
                    return 0;
                }));
        registry.register(simple("list_multi", 3, 3, true, true, true,
                fixed(DataType.DOUBLE, ValueShape.SEQUENCE), unsupported("list_multi")));
        registry.register(simple("div_num", 2, 2, true, true, false,
                fixed(DataType.DOUBLE, ValueShape.SCALAR),
                args -> {
                    double value = asNumber(args.getFirst()).doubleValue();
                    Map<?, ?> params = asMap(args.getLast());
                    double divisor = getDouble(params, "divisor", 1.0);
                    if (divisor == 0.0) {
                        throw new IllegalArgumentException("divisor must not be zero");
                    }
                    return value / divisor;
                }));
        registry.register(simple("round", 1, 1, true, false, false,
                fixed(DataType.INT, ValueShape.SCALAR),
                args -> Math.toIntExact(Math.round(asNumber(args.getFirst()).doubleValue()))));
        registry.register(simple("dis2xl", 2, 2, true, true, false,
                fixed(DataType.INT, ValueShape.SCALAR), unsupported("dis2xl")));
        registry.register(simple("default_key_if", 2, 2, true, true, false,
                passThrough(0), unsupported("default_key_if")));
    }

    private static void registerOpsListOperators(OperatorRegistry registry) {
        registry.register(simple("discrete", 2, 2, true, true, false,
                fixed(DataType.INT, ValueShape.SCALAR), unsupported("discrete")));
        registry.register(simple("log_base", 3, 3, true, false, false,
                fixed(DataType.DOUBLE, ValueShape.SCALAR),
                args -> {
                    double value = asNumber(args.get(0)).doubleValue();
                    double base = asNumber(args.get(1)).doubleValue();
                    double upbound = asNumber(args.get(2)).doubleValue();
                    if (!Double.isFinite(base) || base <= 0.0 || base == 1.0) {
                        throw new IllegalArgumentException(
                                "base must be finite, greater than zero, and not equal to one");
                    }
                    return Math.log(Math.min(value, upbound)) / Math.log(base);
                }));
        registry.register(simple("slice_by_indices", 2, 2, true, true, true,
                passThrough(0), unsupported("slice_by_indices")));
        registry.register(simple("find_indices", 2, 2, true, false, true,
                fixed(DataType.INT, ValueShape.SEQUENCE), unsupported("find_indices")));
        registry.register(simple("get_seq_length", 1, 1, true, false, false,
                fixed(DataType.INT, ValueShape.SCALAR), unsupported("get_seq_length")));
        registry.register(simple("count_distinct", 1, 1, true, false, false,
                fixed(DataType.INT, ValueShape.SCALAR), unsupported("count_distinct")));
        registry.register(simple("zip_concat", 2, Integer.MAX_VALUE, true, false, true,
                fixed(DataType.STRING, ValueShape.SEQUENCE), unsupported("zip_concat")));
        registry.register(simple("calc_delta_seq", 2, 2, true, false, true,
                fixed(DataType.DOUBLE, ValueShape.SEQUENCE),
                args -> {
                    Object input = args.getFirst();
                    if (input instanceof SequenceValue) {
                        throw new UnsupportedOperationException("TODO: calc_delta_seq");
                    }
                    if (!(input instanceof Collection<?> collection)) {
                        throw new IllegalArgumentException(
                                "calc_delta_seq expects a collection input, got: " + input);
                    }
                    double base = asNumber(args.get(1)).doubleValue();
                    List<Double> result = new ArrayList<>(collection.size());
                    for (Object value : collection) {
                        result.add(base - asNumber(value).doubleValue());
                    }
                    return List.copyOf(result);
                }));
    }

    private static java.util.function.Function<List<LogicalNode>, OperatorInference> passThrough(
            int inputIndex) {
        return inputs -> {
            LogicalNode input = inputs.get(inputIndex);
            return new OperatorInference(input.outputType(), unionScopes(inputs), input.valueShape());
        };
    }

    private static java.util.function.Function<List<LogicalNode>, OperatorInference> fixed(
            DataType outputType,
            ValueShape valueShape) {
        return inputs -> new OperatorInference(outputType, unionScopes(inputs), valueShape);
    }

    private static java.util.function.Function<List<Object>, Object> unsupported(String name) {
        return args -> {
            throw new UnsupportedOperationException("TODO: " + name);
        };
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

    private static Object evaluateFindListIndexTyped(List<Object> args) {
        List<?> sequence = asList(args.get(0), "find_list_index_typed", "sequence");
        Object target = args.get(1);
        List<Integer> indices = new ArrayList<>();
        for (int index = 0; index < sequence.size(); index++) {
            if (Objects.equals(sequence.get(index), target)) indices.add(index);
        }
        return nullableImmutableList(indices);
    }

    private static Object evaluateListIndexTyped(List<Object> args) {
        List<?> sequence = asList(args.get(0), "list_index_typed", "sequence");
        List<?> indices = asList(args.get(1), "list_index_typed", "indices");
        List<Object> result = new ArrayList<>(indices.size());
        for (int position = 0; position < indices.size(); position++) {
            int index = asSequenceIndex(indices.get(position), position, sequence.size());
            result.add(sequence.get(index));
        }
        return nullableImmutableList(result);
    }

    private static Object evaluateGreaterInSequenceTyped(List<Object> args) {
        List<?> sequence = asList(args.get(0), "greater_in_sequence_typed", "sequence");
        Number base = asNumber(args.get(1));
        Map<?, ?> config = asMap(args.get(2));
        Object marginValue = config.get("margin");
        if (!(marginValue instanceof Number marginNumber)) {
            throw new IllegalArgumentException(
                    "greater_in_sequence_typed requires numeric margin");
        }
        double margin = marginNumber.doubleValue();
        if (!Double.isFinite(margin) || margin < 0.0) {
            throw new IllegalArgumentException(
                    "greater_in_sequence_typed margin must be finite and non-negative");
        }
        double threshold = base.doubleValue() - margin;
        List<Integer> indices = new ArrayList<>();
        for (int index = 0; index < sequence.size(); index++) {
            Object element = sequence.get(index);
            if (!(element instanceof Number number)) {
                throw new IllegalArgumentException(
                        "greater_in_sequence_typed requires numeric element at index " + index);
            }
            if (number.doubleValue() > threshold) indices.add(index);
        }
        return nullableImmutableList(indices);
    }

    private static List<?> asList(Object value, String operator, String argument) {
        if (value instanceof List<?> list) return list;
        throw new IllegalArgumentException(
                operator + " expects List for " + argument + ", got: "
                        + (value == null ? "null" : value.getClass().getName()));
    }

    private static int asSequenceIndex(Object value, int position, int sequenceSize) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(
                    "list_index_typed index at position " + position + " is not numeric: " + value);
        }
        long longValue;
        try {
            if (number instanceof java.math.BigDecimal decimal) {
                longValue = decimal.longValueExact();
            } else if (number instanceof java.math.BigInteger integer) {
                longValue = integer.longValueExact();
            } else {
                double doubleValue = number.doubleValue();
                longValue = number.longValue();
                if (!Double.isFinite(doubleValue) || doubleValue != longValue) {
                    throw new IllegalArgumentException(
                            "list_index_typed index at position " + position
                                    + " is out of bounds: " + value + ", size=" + sequenceSize);
                }
            }
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(
                    "list_index_typed index at position " + position
                            + " is out of bounds: " + value + ", size=" + sequenceSize);
        }
        if (longValue < 0 || longValue >= sequenceSize) {
            throw new IllegalArgumentException(
                    "list_index_typed index at position " + position
                            + " is out of bounds: " + value + ", size=" + sequenceSize);
        }
        return (int) longValue;
    }

    private static <T> List<T> nullableImmutableList(List<T> values) {
        return java.util.Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static double getDouble(Map<?, ?> params, String key, double defaultValue) {
        Object value = params.get(key);
        return value == null ? defaultValue : asNumber(value).doubleValue();
    }
}
