package com.example.featuredag.operator;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.ValueShape;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry shared by logical inference and runtime execution.
 *
 * 算子注册表：同时服务逻辑层与运行时——构建期用 require/infer 校验算子并推断
 * 输出类型/实体域/值形状（C6），运行期用 evaluate 执行算子求值；
 * 每个算子名只能注册一次。
 */
public final class OperatorRegistry {
    private final Map<String, OperatorDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, BatchOperatorKernel> batchKernels = new ConcurrentHashMap<>();
    private final Map<String, BatchOperatorKernel> scalarBatchAdapters = new ConcurrentHashMap<>();

    public OperatorRegistry register(OperatorDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        OperatorDefinition previous = definitions.putIfAbsent(definition.name(), definition);
        if (previous != null) {
            throw new IllegalArgumentException("Operator already registered: " + definition.name());
        }
        BatchOperatorKernel scalarAdapter = new SingleLoopBatchOperatorKernel(definition);
        scalarBatchAdapters.put(definition.name(), scalarAdapter);
        batchKernels.put(
                definition.name(),
                definition instanceof BatchOperatorKernel nativeBatch ? nativeBatch : scalarAdapter);
        return this;
    }

    public OperatorDefinition require(String name) {
        OperatorDefinition definition = definitions.get(name);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown operator: " + name);
        }
        return definition;
    }

    public Optional<OperatorDefinition> find(String name) {
        return Optional.ofNullable(definitions.get(name));
    }

    public OperatorInference infer(
            String name,
            List<? extends OperatorInputMetadata> inputs) {
        OperatorDefinition definition = require(name);
        validateArity(definition, inputs.size());
        return definition.infer(List.copyOf(inputs));
    }

    public Object evaluate(String name, List<Object> arguments) {
        OperatorDefinition definition = require(name);
        validateArity(definition, arguments.size());
        return definition.evaluate(arguments);
    }

    public BatchOperatorResult evaluateBatch(String name, BatchOperatorCall call) {
        return evaluateBatch(name, call, batchKernelKind(name));
    }

    public BatchOperatorResult evaluateBatch(
            String name,
            BatchOperatorCall call,
            BatchKernelKind plannedKind) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(plannedKind, "plannedKind");
        OperatorDefinition definition = require(name);
        validateArity(definition, call.arguments().size());
        BatchOperatorKernel registered = batchKernel(definition);
        BatchOperatorKernel kernel;
        if (plannedKind == BatchKernelKind.SCALAR_ADAPTER) {
            kernel = scalarBatchAdapter(name);
        } else {
            if (registered.batchKernelKind() != BatchKernelKind.NATIVE) {
                throw new IllegalStateException(
                        "Physical plan requires native Batch kernel for operator " + name);
            }
            kernel = registered;
        }
        BatchOperatorResult result = kernel.evaluateBatch(call);
        if (result.values().size() != call.rowCount()) {
            throw new IllegalStateException(
                    "Batch operator " + name + " returned " + result.values().size()
                            + " rows, expected " + call.rowCount());
        }
        return result;
    }

    public BatchKernelKind batchKernelKind(String name) {
        require(name);
        return batchKernel(name).batchKernelKind();
    }

    private BatchOperatorKernel batchKernel(OperatorDefinition definition) {
        return batchKernel(definition.name());
    }

    private BatchOperatorKernel batchKernel(String name) {
        BatchOperatorKernel kernel = batchKernels.get(name);
        if (kernel == null) throw new IllegalStateException("Missing batch kernel for operator: " + name);
        return kernel;
    }

    private BatchOperatorKernel scalarBatchAdapter(String name) {
        BatchOperatorKernel kernel = scalarBatchAdapters.get(name);
        if (kernel == null) {
            throw new IllegalStateException("Missing scalar Batch adapter for operator: " + name);
        }
        return kernel;
    }

    public <T extends OperatorSemantic> Optional<T> semantic(String operatorName, Class<T> semanticType) {
        Objects.requireNonNull(semanticType, "semanticType");
        OperatorDefinition definition = definitions.get(operatorName);
        if (definition == null) return Optional.empty();
        return definition.semantics().stream()
                .filter(semanticType::isInstance)
                .map(semanticType::cast)
                .findFirst();
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
                1_000L,
                List.of(new KeyedSequenceFilterSemantic(0, 1, SequenceKeyDomains.INDUSTRY)),
                inputs -> new OperatorInference(DataType.EVENT_SEQUENCE, unionScopes(inputs), ValueShape.SEQUENCE),
                args -> {
                    OperatorSequence sequence = asSequence(args.get(0));
                    String industry = String.valueOf(args.get(1));
                    return sequence.filterByIndustry(industry);
                }));

        registry.register(rowWiseSimple("count", 1, 1, true, false, true,
                10L,
                List.of(new SequenceCardinalitySemantic(0)),
                inputs -> {
                    OperatorInputMetadata input = inputs.getFirst();
                    if (input.valueShape() != ValueShape.SEQUENCE
                            && input.outputType() != DataType.OBJECT) {
                        throw new IllegalArgumentException(
                                "count expects a sequence/collection input, got type="
                                        + input.outputType() + ", shape=" + input.valueShape()
                                        + " from " + input.sourceFeatureName());
                    }
                    return new OperatorInference(DataType.INT, unionScopes(inputs), ValueShape.SCALAR);
                },
                arguments -> evaluateCountValue(arguments.getFirst())));

        registry.register(rowWiseSimple("add", 2, Integer.MAX_VALUE, true, false, false,
                inputs -> new OperatorInference(DataType.DOUBLE, unionScopes(inputs), ValueShape.SCALAR),
                OperatorRegistry::evaluateAdd));

        registry.register(rowWiseSimple("log", 1, 1, true, false, false,
                inputs -> new OperatorInference(DataType.DOUBLE, unionScopes(inputs), ValueShape.SCALAR),
                arguments -> Math.log(asNumber(arguments.getFirst()).doubleValue())));

        registry.register(rowWiseSimple("multiply", 2, 2, true, false, false,
                inputs -> new OperatorInference(DataType.DOUBLE, unionScopes(inputs), ValueShape.SCALAR),
                arguments -> asNumber(arguments.get(0)).doubleValue()
                        * asNumber(arguments.get(1)).doubleValue()));

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
        registry.register(curriedSimple("slice_v3_typed", 2, 2, true, true, true,
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
        registry.register(rowWiseSimple("div_num", 2, 2, true, true, false,
                fixed(DataType.DOUBLE, ValueShape.SCALAR),
                OperatorRegistry::evaluateDivNum));
        registry.register(rowWiseSimple("round", 1, 1, true, false, false,
                fixed(DataType.INT, ValueShape.SCALAR),
                arguments -> Math.toIntExact(Math.round(
                        asNumber(arguments.getFirst()).doubleValue()))));
        registry.register(rowWiseSimple("div", 2, 2, true, false, false,
                fixed(DataType.DOUBLE, ValueShape.SCALAR),
                OperatorRegistry::evaluateDiv));
        registry.register(rowWiseSimple("least", 2, Integer.MAX_VALUE, true, false, false,
                inputs -> {
                    boolean allInt = true;
                    for (OperatorInputMetadata input : inputs) {
                        if (input.outputType() != DataType.INT) {
                            allInt = false;
                            break;
                        }
                    }
                    return new OperatorInference(
                            allInt ? DataType.INT : DataType.DOUBLE,
                            unionScopes(inputs),
                            ValueShape.SCALAR);
                },
                OperatorRegistry::evaluateLeast));
        registry.register(simple("dis2xl", 2, 2, true, true, false,
                fixed(DataType.INT, ValueShape.SCALAR), unsupported("dis2xl")));
        registry.register(simple("default_key_if", 2, 2, true, true, false,
                passThrough(0), unsupported("default_key_if")));
    }

    private static void registerOpsListOperators(OperatorRegistry registry) {
        registry.register(simple("discrete", 2, 2, true, true, false,
                fixed(DataType.INT, ValueShape.SCALAR), OperatorRegistry::evaluateDiscrete));
        registry.register(simple("log_base", 3, 3, true, false, false,
                fixed(DataType.DOUBLE, ValueShape.SCALAR),
                OperatorRegistry::evaluateLogBase));
        registry.register(simple("slice_by_indices", 2, 2, true, true, true,
                passThrough(0), OperatorRegistry::evaluateSliceByIndices));
        registry.register(simple("find_indices", 2, 2, true, false, true,
                fixed(DataType.INT, ValueShape.SEQUENCE), OperatorRegistry::evaluateFindIndices));
        registry.register(simple("get_seq_length", 1, 1, true, false, false,
                fixed(DataType.INT, ValueShape.SCALAR), OperatorRegistry::evaluateGetSeqLength));
        registry.register(simple("count_distinct", 1, 1, true, false, false,
                fixed(DataType.INT, ValueShape.SCALAR), OperatorRegistry::evaluateCountDistinct));
        registry.register(simple("zip_concat", 2, Integer.MAX_VALUE, true, true, true,
                fixed(DataType.STRING, ValueShape.SEQUENCE), OperatorRegistry::evaluateZipConcat));
        registry.register(simple("calc_delta_seq", 2, 2, true, false, true,
                fixed(DataType.DOUBLE, ValueShape.SEQUENCE), OperatorRegistry::evaluateCalcDeltaSeq));
    }

    private static Object evaluateCountValue(Object value) {
        if (value == null) return 0;
        if (value instanceof OperatorSequence sequence) return sequence.size();
        if (value instanceof Collection<?> collection) return collection.size();
        if (value.getClass().isArray()) return java.lang.reflect.Array.getLength(value);
        throw new IllegalArgumentException("count does not support: " + value.getClass());
    }

    private static Object evaluateAdd(RowArguments args) {
        double result = 0.0;
        for (int index = 0; index < args.size(); index++) {
            result += asNumber(args.get(index)).doubleValue();
        }
        return result;
    }

    private static Object evaluateDivNum(RowArguments args) {
        double value = asNumber(args.getFirst()).doubleValue();
        Map<?, ?> params = asMap(args.getLast());
        double divisor = getDouble(params, "divisor", 1.0);
        if (divisor == 0.0) throw new IllegalArgumentException("divisor must not be zero");
        return value / divisor;
    }

    private static Object evaluateDiv(RowArguments args) {
        double divisor = asNumber(args.getLast()).doubleValue();
        if (divisor == 0.0) throw new IllegalArgumentException("divisor must not be zero");
        return asNumber(args.getFirst()).doubleValue() / divisor;
    }

    private static Object evaluateLeast(RowArguments args) {
        Number minimum = asNumber(args.getFirst());
        boolean returnDouble = isFloatingPoint(minimum);
        for (int index = 1; index < args.size(); index++) {
            Number candidate = asNumber(args.get(index));
            returnDouble |= isFloatingPoint(candidate);
            if (candidate.doubleValue() < minimum.doubleValue()) minimum = candidate;
        }
        if (returnDouble) return minimum.doubleValue();
        return minimum.intValue();
    }

    private static Object evaluateDiscrete(List<Object> args) {
        BigDecimal value = asPreciseDecimal(
                asNumber(args.get(0)), "discrete requires a finite numeric value");
        List<?> boundaries = asList(args.get(1), "discrete", "discrete_key");
        BigDecimal previous = null;
        int bucket = 0;
        for (int index = 0; index < boundaries.size(); index++) {
            Object boundary = boundaries.get(index);
            if (!(boundary instanceof Number number)) {
                throw new IllegalArgumentException(
                        "discrete boundary at index " + index + " is not numeric: " + boundary);
            }
            BigDecimal current = asPreciseDecimal(
                    number, "discrete boundary at index " + index + " must be finite");
            if (previous != null && current.compareTo(previous) <= 0) {
                throw new IllegalArgumentException(
                        "discrete boundaries must be strictly increasing at index " + index);
            }
            if (value.compareTo(current) >= 0) bucket++;
            previous = current;
        }
        return bucket;
    }

    private static Object evaluateLogBase(List<Object> args) {
        double value = finiteDouble(args.get(0), "log_base value");
        double base = finiteDouble(args.get(1), "log_base base");
        double upbound = finiteDouble(args.get(2), "log_base upbound");
        if (base <= 0.0 || base == 1.0) {
            throw new IllegalArgumentException(
                    "log_base base must be greater than zero and not equal to one");
        }
        if (value <= 0.0) {
            throw new IllegalArgumentException("log_base value must be greater than zero");
        }
        if (upbound <= 0.0) {
            throw new IllegalArgumentException("log_base upbound must be greater than zero");
        }
        return Math.log(Math.min(value, upbound)) / Math.log(base);
    }

    private static Object evaluateSliceByIndices(List<Object> args) {
        List<?> sequence = asList(args.get(0), "slice_by_indices", "sequence");
        List<?> indices = asList(args.get(1), "slice_by_indices", "indices");
        List<Object> result = new ArrayList<>(indices.size());
        for (int position = 0; position < indices.size(); position++) {
            int index = asSequenceIndex(
                    indices.get(position), position, sequence.size(), "slice_by_indices");
            result.add(sequence.get(index));
        }
        return nullableImmutableList(result);
    }

    private static Object evaluateFindIndices(List<Object> args) {
        List<?> sequence = asList(args.get(0), "find_indices", "sequence");
        Object target = args.get(1);
        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < sequence.size(); index++) {
            if (Objects.equals(sequence.get(index), target)) result.add(index);
        }
        return List.copyOf(result);
    }

    private static Object evaluateGetSeqLength(List<Object> args) {
        Object sequence = args.getFirst();
        if (sequence instanceof OperatorSequence value) return value.size();
        if (sequence instanceof Collection<?> collection) return collection.size();
        if (sequence != null && sequence.getClass().isArray()) {
            return java.lang.reflect.Array.getLength(sequence);
        }
        throw new IllegalArgumentException("get_seq_length expects a sequence, got: " + typeName(sequence));
    }

    private static Object evaluateCountDistinct(List<Object> args) {
        Object sequence = args.getFirst();
        Collection<?> values;
        if (sequence instanceof OperatorSequence value) {
            List<Object> events = new ArrayList<>(value.size());
            for (int index = 0; index < value.size(); index++) events.add(value.elementAt(index));
            values = events;
        } else if (sequence instanceof Collection<?> collection) {
            values = collection;
        } else {
            throw new IllegalArgumentException("count_distinct expects a sequence, got: " + typeName(sequence));
        }
        return new LinkedHashSet<>(values).size();
    }

    private static Object evaluateZipConcat(List<Object> args) {
        int sequenceCount = args.size();
        String delimiter = "#";
        Object last = args.getLast();
        if (last instanceof Map<?, ?> config) {
            sequenceCount--;
            Object configured = config.get("delimiter");
            if (configured != null) delimiter = String.valueOf(configured);
        }
        if (sequenceCount < 2) {
            throw new IllegalArgumentException("zip_concat requires at least two sequences");
        }
        List<List<?>> sequences = new ArrayList<>(sequenceCount);
        int size = -1;
        for (int index = 0; index < sequenceCount; index++) {
            List<?> sequence = asList(args.get(index), "zip_concat", "sequence " + index);
            if (size < 0) size = sequence.size();
            if (sequence.size() != size) {
                throw new IllegalArgumentException(
                        "zip_concat requires sequences of equal length; sequence " + index
                                + " has length " + sequence.size() + ", expected " + size);
            }
            sequences.add(sequence);
        }
        List<String> result = new ArrayList<>(size);
        for (int row = 0; row < size; row++) {
            StringBuilder joined = new StringBuilder();
            for (int column = 0; column < sequences.size(); column++) {
                if (column > 0) joined.append(delimiter);
                joined.append(String.valueOf(sequences.get(column).get(row)));
            }
            result.add(joined.toString());
        }
        return List.copyOf(result);
    }

    private static Object evaluateCalcDeltaSeq(List<Object> args) {
        List<?> sequence = asList(args.get(0), "calc_delta_seq", "sequence");
        double base = finiteDouble(args.get(1), "calc_delta_seq base");
        List<Double> result = new ArrayList<>(sequence.size());
        for (int index = 0; index < sequence.size(); index++) {
            double value = finiteDouble(
                    sequence.get(index), "calc_delta_seq element at index " + index);
            result.add(value - base);
        }
        return List.copyOf(result);
    }

    private static java.util.function.Function<List<OperatorInputMetadata>, OperatorInference> passThrough(
            int inputIndex) {
        return inputs -> {
            OperatorInputMetadata input = inputs.get(inputIndex);
            return new OperatorInference(input.outputType(), unionScopes(inputs), input.valueShape());
        };
    }

    private static java.util.function.Function<List<OperatorInputMetadata>, OperatorInference> fixed(
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
            java.util.function.Function<List<OperatorInputMetadata>, OperatorInference> inference,
            java.util.function.Function<List<Object>, Object> evaluator) {
        return simple(
                name, minArgs, maxArgs, deterministic, parameterized, supportsView,
                false, 1L, List.of(), inference, evaluator);
    }

    private static OperatorDefinition simple(
            String name,
            int minArgs,
            int maxArgs,
            boolean deterministic,
            boolean parameterized,
            boolean supportsView,
            java.util.function.Function<List<OperatorInputMetadata>, OperatorInference> inference,
            java.util.function.Function<List<Object>, Object> evaluator,
            BatchOperatorKernel batchKernel) {
        return simple(
                name, minArgs, maxArgs, deterministic, parameterized, supportsView,
                false, 1L, List.of(), inference, evaluator, batchKernel);
    }

    private static OperatorDefinition rowWiseSimple(
            String name,
            int minArgs,
            int maxArgs,
            boolean deterministic,
            boolean parameterized,
            boolean supportsView,
            java.util.function.Function<List<OperatorInputMetadata>, OperatorInference> inference,
            RowEvaluator evaluator) {
        return simple(
                name, minArgs, maxArgs, deterministic, parameterized, supportsView,
                inference, singleEvaluator(evaluator), nativeBatch(evaluator));
    }

    private static OperatorDefinition curriedSimple(
            String name,
            int minArgs,
            int maxArgs,
            boolean deterministic,
            boolean parameterized,
            boolean supportsView,
            java.util.function.Function<List<OperatorInputMetadata>, OperatorInference> inference,
            java.util.function.Function<List<Object>, Object> evaluator) {
        return simple(
                name, minArgs, maxArgs, deterministic, parameterized, supportsView,
                true, 1L, List.of(), inference, evaluator);
    }

    private static OperatorDefinition simple(
            String name,
            int minArgs,
            int maxArgs,
            boolean deterministic,
            boolean parameterized,
            boolean supportsView,
            long estimatedCost,
            List<OperatorSemantic> semantics,
            java.util.function.Function<List<OperatorInputMetadata>, OperatorInference> inference,
            java.util.function.Function<List<Object>, Object> evaluator) {
        return simple(
                name, minArgs, maxArgs, deterministic, parameterized, supportsView,
                false, estimatedCost, semantics, inference, evaluator);
    }

    private static OperatorDefinition simple(
            String name,
            int minArgs,
            int maxArgs,
            boolean deterministic,
            boolean parameterized,
            boolean supportsView,
            long estimatedCost,
            List<OperatorSemantic> semantics,
            java.util.function.Function<List<OperatorInputMetadata>, OperatorInference> inference,
            java.util.function.Function<List<Object>, Object> evaluator,
            BatchOperatorKernel batchKernel) {
        return simple(
                name, minArgs, maxArgs, deterministic, parameterized, supportsView,
                false, estimatedCost, semantics, inference, evaluator, batchKernel);
    }

    private static OperatorDefinition rowWiseSimple(
            String name,
            int minArgs,
            int maxArgs,
            boolean deterministic,
            boolean parameterized,
            boolean supportsView,
            long estimatedCost,
            List<OperatorSemantic> semantics,
            java.util.function.Function<List<OperatorInputMetadata>, OperatorInference> inference,
            RowEvaluator evaluator) {
        return simple(
                name, minArgs, maxArgs, deterministic, parameterized, supportsView,
                estimatedCost, semantics, inference,
                singleEvaluator(evaluator), nativeBatch(evaluator));
    }

    private static OperatorDefinition simple(
            String name,
            int minArgs,
            int maxArgs,
            boolean deterministic,
            boolean parameterized,
            boolean supportsView,
            boolean supportsCurriedInvocation,
            long estimatedCost,
            List<OperatorSemantic> semantics,
            java.util.function.Function<List<OperatorInputMetadata>, OperatorInference> inference,
            java.util.function.Function<List<Object>, Object> evaluator) {
        return simple(
                name, minArgs, maxArgs, deterministic, parameterized, supportsView,
                supportsCurriedInvocation, estimatedCost, semantics, inference, evaluator, null);
    }

    private static OperatorDefinition simple(
            String name,
            int minArgs,
            int maxArgs,
            boolean deterministic,
            boolean parameterized,
            boolean supportsView,
            boolean supportsCurriedInvocation,
            long estimatedCost,
            List<OperatorSemantic> semantics,
            java.util.function.Function<List<OperatorInputMetadata>, OperatorInference> inference,
            java.util.function.Function<List<Object>, Object> evaluator,
            BatchOperatorKernel batchKernel) {
        return new SimpleOperatorDefinition(
                name,
                minArgs,
                maxArgs,
                deterministic,
                parameterized,
                supportsView,
                supportsCurriedInvocation,
                estimatedCost,
                semantics,
                inference,
                evaluator,
                batchKernel);
    }

    private interface RowArguments {
        int size();

        Object get(int index);

        default Object getFirst() {
            return get(0);
        }

        default Object getLast() {
            return get(size() - 1);
        }
    }

    private record ListRowArguments(List<Object> values) implements RowArguments {
        @Override public int size() { return values.size(); }
        @Override public Object get(int index) { return values.get(index); }
    }

    private static final class BatchRowArguments implements RowArguments {
        private final BatchOperatorCall call;
        private int rowIndex;

        private BatchRowArguments(BatchOperatorCall call) {
            this.call = call;
        }

        private void moveTo(int value) {
            rowIndex = value;
        }

        @Override public int size() { return call.arguments().size(); }
        @Override public Object get(int index) {
            return call.arguments().get(index).valueAt(rowIndex);
        }
    }

    @FunctionalInterface
    private interface RowEvaluator {
        Object evaluate(RowArguments arguments);
    }

    private static java.util.function.Function<List<Object>, Object> singleEvaluator(
            RowEvaluator evaluator) {
        return arguments -> evaluator.evaluate(new ListRowArguments(arguments));
    }

    private static BatchOperatorKernel nativeBatch(RowEvaluator evaluator) {
        return call -> {
            List<Object> result = new ArrayList<>(call.rowCount());
            BatchRowArguments arguments = new BatchRowArguments(call);
            for (int rowIndex = 0; rowIndex < call.rowCount(); rowIndex++) {
                try {
                    arguments.moveTo(rowIndex);
                    result.add(evaluator.evaluate(arguments));
                } catch (RuntimeException error) {
                    throw new BatchOperatorEvaluationException(rowIndex, error);
                }
            }
            return new BatchOperatorResult(new ListBatchColumn(result));
        };
    }

    private static final class SimpleOperatorDefinition
            implements OperatorDefinition, BatchOperatorKernel {
        private final String name;
        private final int minArgs;
        private final int maxArgs;
        private final boolean deterministic;
        private final boolean parameterized;
        private final boolean supportsView;
        private final boolean supportsCurriedInvocation;
        private final long estimatedCost;
        private final List<OperatorSemantic> semantics;
        private final java.util.function.Function<List<OperatorInputMetadata>, OperatorInference> inference;
        private final java.util.function.Function<List<Object>, Object> evaluator;
        private final BatchOperatorKernel batchKernel;

        private SimpleOperatorDefinition(
                String name,
                int minArgs,
                int maxArgs,
                boolean deterministic,
                boolean parameterized,
                boolean supportsView,
                boolean supportsCurriedInvocation,
                long estimatedCost,
                List<OperatorSemantic> semantics,
                java.util.function.Function<List<OperatorInputMetadata>, OperatorInference> inference,
                java.util.function.Function<List<Object>, Object> evaluator,
                BatchOperatorKernel batchKernel) {
            this.name = name;
            this.minArgs = minArgs;
            this.maxArgs = maxArgs;
            this.deterministic = deterministic;
            this.parameterized = parameterized;
            this.supportsView = supportsView;
            this.supportsCurriedInvocation = supportsCurriedInvocation;
            this.estimatedCost = estimatedCost;
            this.semantics = List.copyOf(semantics);
            this.inference = inference;
            this.evaluator = evaluator;
            this.batchKernel = batchKernel == null
                    ? new SingleLoopBatchOperatorKernel(this)
                    : batchKernel;
        }

        @Override public String name() { return name; }
        @Override public int minArguments() { return minArgs; }
        @Override public int maxArguments() { return maxArgs; }
        @Override public boolean deterministic() { return deterministic; }
        @Override public boolean parameterized() { return parameterized; }
        @Override public boolean supportsSequenceView() { return supportsView; }
        @Override public boolean supportsCurriedInvocation() { return supportsCurriedInvocation; }
        @Override public long estimatedCost() { return estimatedCost; }
        @Override public List<OperatorSemantic> semantics() { return semantics; }
        @Override public OperatorInference infer(List<OperatorInputMetadata> inputs) {
            return inference.apply(inputs);
        }
        @Override public Object evaluate(List<Object> arguments) { return evaluator.apply(arguments); }
        @Override public BatchOperatorResult evaluateBatch(BatchOperatorCall call) {
            return batchKernel.evaluateBatch(call);
        }
        @Override public BatchKernelKind batchKernelKind() {
            return batchKernel.batchKernelKind();
        }
    }

    private static Set<EntityScope> unionScopes(List<OperatorInputMetadata> inputs) {
        Set<EntityScope> result = new LinkedHashSet<>();
        for (OperatorInputMetadata input : inputs) result.addAll(input.entityScopes());
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

    private static boolean isFloatingPoint(Number value) {
        return value instanceof Float
                || value instanceof Double
                || value instanceof java.math.BigDecimal;
    }

    private static Map<?, ?> asMap(Object value) {
        if (value instanceof Map<?, ?> map) return map;
        throw new IllegalArgumentException("Expected object/map, got: " + value);
    }

    private static OperatorSequence asSequence(Object value) {
        if (value instanceof OperatorSequence sequence) return sequence;
        throw new IllegalArgumentException("Expected operator sequence, got: " + value);
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
        BigDecimal baseValue = asPreciseDecimal(
                base, "greater_in_sequence_typed requires finite numeric base");
        Map<?, ?> config = asMap(args.get(2));
        Object marginValue = config.get("margin");
        if (!(marginValue instanceof Number marginNumber)) {
            throw new IllegalArgumentException(
                    "greater_in_sequence_typed requires numeric margin");
        }
        BigDecimal margin = asPreciseDecimal(
                marginNumber,
                "greater_in_sequence_typed margin must be finite and non-negative");
        if (margin.signum() < 0) {
            throw new IllegalArgumentException(
                    "greater_in_sequence_typed margin must be finite and non-negative");
        }
        BigDecimal threshold = baseValue.subtract(margin);
        List<Integer> indices = new ArrayList<>();
        for (int index = 0; index < sequence.size(); index++) {
            Object element = sequence.get(index);
            if (!(element instanceof Number number)) {
                throw new IllegalArgumentException(
                        "greater_in_sequence_typed requires numeric element at index " + index);
            }
            BigDecimal elementValue = asPreciseDecimal(
                    number,
                    "greater_in_sequence_typed requires numeric element at index " + index);
            if (elementValue.compareTo(threshold) > 0) indices.add(index);
        }
        return nullableImmutableList(indices);
    }

    private static BigDecimal asPreciseDecimal(Number number, String errorMessage) {
        if (number instanceof BigDecimal decimal) return decimal;
        if (number instanceof BigInteger integer) return new BigDecimal(integer);
        if (number instanceof Byte || number instanceof Short
                || number instanceof Integer || number instanceof Long) {
            return BigDecimal.valueOf(number.longValue());
        }
        if (number instanceof Float floatValue) {
            if (!Float.isFinite(floatValue)) throw new IllegalArgumentException(errorMessage);
            return new BigDecimal(Float.toString(floatValue));
        }
        if (number instanceof Double doubleValue) {
            if (!Double.isFinite(doubleValue)) throw new IllegalArgumentException(errorMessage);
            return BigDecimal.valueOf(doubleValue);
        }
        try {
            return new BigDecimal(number.toString());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(errorMessage);
        }
    }

    private static List<?> asList(Object value, String operator, String argument) {
        if (value instanceof List<?> list) return list;
        throw new IllegalArgumentException(
                operator + " expects List for " + argument + ", got: "
                        + (value == null ? "null" : value.getClass().getName()));
    }

    private static int asSequenceIndex(Object value, int position, int sequenceSize) {
        return asSequenceIndex(value, position, sequenceSize, "list_index_typed");
    }

    private static int asSequenceIndex(
            Object value,
            int position,
            int sequenceSize,
            String operator) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(
                    operator + " index at position " + position + " is not numeric: " + value);
        }
        int index;
        try {
            index = asPreciseDecimal(number, "invalid sequence index").intValueExact();
        } catch (ArithmeticException | IllegalArgumentException error) {
            throw invalidSequenceIndex(operator, position, value, sequenceSize);
        }
        if (index < 0 || index >= sequenceSize) {
            throw invalidSequenceIndex(operator, position, value, sequenceSize);
        }
        return index;
    }

    private static IllegalArgumentException invalidSequenceIndex(
            String operator,
            int position,
            Object value,
            int sequenceSize) {
        return new IllegalArgumentException(
                operator + " index at position " + position
                        + " is out of bounds: " + value + ", size=" + sequenceSize);
    }

    private static double finiteDouble(Object value, String argument) {
        double result = asNumber(value).doubleValue();
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException(argument + " must be finite");
        }
        return result;
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static <T> List<T> nullableImmutableList(List<T> values) {
        return java.util.Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static double getDouble(Map<?, ?> params, String key, double defaultValue) {
        Object value = params.get(key);
        return value == null ? defaultValue : asNumber(value).doubleValue();
    }
}
