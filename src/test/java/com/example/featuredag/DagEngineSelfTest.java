package com.example.featuredag;

import com.example.featuredag.api.FeatureValueCodecSelfTest;
import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.FeatureDefinition;
import com.example.featuredag.definition.OutputPolicy;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.demo.OfflineBatchOperatorsDemo;
import com.example.featuredag.demo.ScalarOperatorsDemo;
import com.example.featuredag.demo.SequenceOperatorsDemo;
import com.example.featuredag.expression.AstCall;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.operator.BatchKernelKind;
import com.example.featuredag.operator.OperatorDefinition;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.operator.builtin.InitialBusinessOperators;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Dependency-free self test. Run with java -ea. */
public final class DagEngineSelfTest {
    private static final Set<String> INITIAL_OPERATOR_NAMES = Set.of(
            "discrete",
            "log_base",
            "slice_by_indices",
            "find_indices",
            "get_seq_length",
            "count_distinct",
            "zip_concat",
            "calc_delta_seq");

    private DagEngineSelfTest() {}

    public static void main(String[] args) {
        FeatureValueCodecSelfTest.run();
        testInitialOperatorRegistry();
        testInitialOperatorEvaluation();
        testInitialOperatorValidation();
        testInitialOperatorExpressionsBuildAndInfer();
        testInitialOperatorPublicApiDemos();
        System.out.println("All DAG engine self tests passed.");
    }

    private static void testInitialOperatorRegistry() {
        List<OperatorDefinition> definitions = InitialBusinessOperators.definitions();
        Set<String> names = definitions.stream()
                .map(OperatorDefinition::name)
                .collect(Collectors.toSet());

        assert definitions.size() == 8 : "Expected 8 operators, got " + definitions.size();
        assert names.equals(INITIAL_OPERATOR_NAMES) : names;
        assert definitions.stream()
                .map(OperatorDefinition::getClass)
                .collect(Collectors.toSet())
                .size() == definitions.size()
                : "Each operator must have its own implementation class";

        Map<String, List<Integer>> arities = Map.of(
                "discrete", List.of(2, 2),
                "log_base", List.of(3, 3),
                "slice_by_indices", List.of(2, 2),
                "find_indices", List.of(2, 2),
                "get_seq_length", List.of(1, 1),
                "count_distinct", List.of(1, 1),
                "zip_concat", List.of(2, Integer.MAX_VALUE),
                "calc_delta_seq", List.of(2, 2));

        OperatorRegistry registry = OperatorRegistry.standard();
        for (String name : INITIAL_OPERATOR_NAMES) {
            OperatorDefinition definition = registry.require(name);
            assert definition.minArguments() == arities.get(name).get(0)
                    : name + " min arity=" + definition.minArguments();
            assert definition.maxArguments() == arities.get(name).get(1)
                    : name + " max arity=" + definition.maxArguments();
            assert registry.batchKernelKind(name) == BatchKernelKind.SCALAR_ADAPTER
                    : name + " should use the standard scalar Batch adapter";
        }
    }

    private static void testInitialOperatorEvaluation() {
        OperatorRegistry registry = OperatorRegistry.standard();

        assert registry.evaluate("discrete", List.of(16, List.of(1, 10, 100))).equals(2);
        assert Math.abs(((Number) registry.evaluate("log_base", List.of(8, 2, 1000)))
                .doubleValue() - 3.0) < 1e-9;
        assert registry.evaluate(
                "slice_by_indices",
                List.of(List.of("a1", "a2", "a3", "a4"), List.of(1, 3)))
                .equals(List.of("a2", "a4"));
        assert registry.evaluate(
                "find_indices", List.of(List.of("a1", "a2", "a3", "a3"), "a3"))
                .equals(List.of(2, 3));
        assert registry.evaluate("get_seq_length", List.of(List.of("a1", "a2", "a3")))
                .equals(3);
        assert registry.evaluate("count_distinct", List.of(List.of("a1", "a2", "a1")))
                .equals(2);
        assert registry.evaluate(
                "zip_concat",
                List.of(List.of("a1", "a2"), List.of("b1", "b2")))
                .equals(List.of("a1#b1", "a2#b2"));
        assert registry.evaluate("calc_delta_seq", List.of(List.of(2, 5, 9), 10))
                .equals(List.of(-8.0, -5.0, -1.0));
    }

    private static void testInitialOperatorValidation() {
        OperatorRegistry registry = OperatorRegistry.standard();

        IllegalArgumentException invalidBase = expectThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("log_base", List.of(8, 1, 1000)));
        assert invalidBase.getMessage().contains("base") : invalidBase.getMessage();

        IllegalArgumentException unorderedBoundaries = expectThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("discrete", List.of(16, List.of(1, 100, 10))));
        assert unorderedBoundaries.getMessage().contains("strictly increasing")
                : unorderedBoundaries.getMessage();

        IllegalArgumentException unequalZipLengths = expectThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate(
                        "zip_concat", List.of(List.of("a1"), List.of("b1", "b2"))));
        assert unequalZipLengths.getMessage().contains("equal length")
                : unequalZipLengths.getMessage();
    }

    private static void testInitialOperatorExpressionsBuildAndInfer() {
        List<OperatorCase> cases = List.of(
                new OperatorCase("discrete", "discrete(a, [1, 10, 100])",
                        DataType.INT, ValueShape.SCALAR),
                new OperatorCase("log_base", "log_base(a, 2, 1000)",
                        DataType.DOUBLE, ValueShape.SCALAR),
                new OperatorCase("slice_by_indices", "slice_by_indices(seq, [0])",
                        DataType.EVENT_SEQUENCE, ValueShape.SEQUENCE),
                new OperatorCase("find_indices", "find_indices(seq, a)",
                        DataType.INT, ValueShape.SEQUENCE),
                new OperatorCase("get_seq_length", "get_seq_length(seq)",
                        DataType.INT, ValueShape.SCALAR),
                new OperatorCase("count_distinct", "count_distinct(seq)",
                        DataType.INT, ValueShape.SCALAR),
                new OperatorCase("zip_concat", "zip_concat(seq, seq2)",
                        DataType.STRING, ValueShape.SEQUENCE),
                new OperatorCase("calc_delta_seq", "calc_delta_seq(seq, a)",
                        DataType.DOUBLE, ValueShape.SEQUENCE));

        OperatorRegistry registry = OperatorRegistry.standard();
        ExpressionParser parser = new ExpressionParser();
        List<FeatureDefinition> definitions = new ArrayList<>(List.of(
                FeatureDefinition.raw("a", DataType.DOUBLE, EntityScope.ITEM, 1.0),
                FeatureDefinition.raw("seq", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                FeatureDefinition.raw("seq2", DataType.EVENT_SEQUENCE, EntityScope.ITEM, null)));
        Set<String> targets = new LinkedHashSet<>();
        Map<String, OperatorCase> casesByFeature = new LinkedHashMap<>();

        for (int index = 0; index < cases.size(); index++) {
            OperatorCase operatorCase = cases.get(index);
            AstCall parsed = (AstCall) parser.parse(operatorCase.expression());
            assert parsed.functionName().equals(operatorCase.operatorName())
                    : operatorCase.expression();
            registry.require(parsed.functionName());

            String featureName = "initial_operator_" + (index + 1);
            definitions.add(FeatureDefinition.builder()
                    .name(featureName)
                    .dataType(DataType.UNKNOWN)
                    .expressionContent(operatorCase.expression())
                    .outputPolicy(OutputPolicy.OUTPUT)
                    .build());
            targets.add(featureName);
            casesByFeature.put(featureName, operatorCase);
        }

        LogicalDag dag = new LogicalDagBuilder(parser, registry).build(definitions, targets);
        assert casesByFeature.size() == 8 : casesByFeature.keySet();
        for (Map.Entry<String, OperatorCase> entry : casesByFeature.entrySet()) {
            OperatorCase operatorCase = entry.getValue();
            assert dag.featureOutput(entry.getKey()).outputType() == operatorCase.outputType()
                    : entry.getKey() + " type=" + dag.featureOutput(entry.getKey()).outputType();
            assert dag.featureOutput(entry.getKey()).valueShape() == operatorCase.valueShape()
                    : entry.getKey() + " shape=" + dag.featureOutput(entry.getKey()).valueShape();
        }
    }

    private static void testInitialOperatorPublicApiDemos() {
        ScalarOperatorsDemo.run();
        SequenceOperatorsDemo.run();
        OfflineBatchOperatorsDemo.run();
    }

    private static <T extends Throwable> T expectThrows(
            Class<T> expectedType,
            ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable failure) {
            if (expectedType.isInstance(failure)) {
                return expectedType.cast(failure);
            }
            throw new AssertionError(
                    "Expected " + expectedType.getName() + " but got " + failure, failure);
        }
        throw new AssertionError("Expected " + expectedType.getName());
    }

    private record OperatorCase(
            String operatorName,
            String expression,
            DataType outputType,
            ValueShape valueShape) {}

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
