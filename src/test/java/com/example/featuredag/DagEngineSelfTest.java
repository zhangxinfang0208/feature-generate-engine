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
import com.example.featuredag.operator.BatchColumn;
import com.example.featuredag.operator.BatchDomain;
import com.example.featuredag.operator.BatchKernelKind;
import com.example.featuredag.operator.BatchLayout;
import com.example.featuredag.operator.BatchOperatorCall;
import com.example.featuredag.operator.BatchOperatorEvaluationException;
import com.example.featuredag.operator.BatchOperatorResult;
import com.example.featuredag.operator.ListBatchColumn;
import com.example.featuredag.operator.OperatorDefinition;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.operator.builtin.InitialBusinessOperators;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
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

    /**
     * 提供原生 BatchOperatorKernel 的算子：批内按 (group, sequence, 参数) 身份键复用
     * 收益显著；其余 4 个（discrete / log_base / slice_by_indices / get_seq_length）
     * 实测批开销反噬，不提供原生 Batch，由 SingleLoopBatchOperatorKernel 逐行适配。
     */
    private static final Set<String> NATIVE_BATCH_OPERATORS = Set.of(
            "find_indices", "count_distinct", "zip_concat", "calc_delta_seq");

    private DagEngineSelfTest() {}

    public static void main(String[] args) {
        FeatureValueCodecSelfTest.run();
        ModelFeatureSetInitialOperatorsSelfTest.run();
        testInitialOperatorRegistry();
        testInitialOperatorEvaluation();
        testInitialOperatorNativeBatchEquivalence();
        testNativeBatchFailureRow();
        testFindIndicesNativeBatchReusesSequenceScan();
        testInitialOperatorValidation();
        testInitialOperatorExpressionsBuildAndInfer();
        testInitialOperatorPublicApiDemos();
        testNativeBatchPerformanceDemo();
        testOperatorBatchComparisonDemo();
        testFeatureExpressionBatchComparisonDemo();
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
            BatchKernelKind expectedKernel = NATIVE_BATCH_OPERATORS.contains(name)
                    ? BatchKernelKind.NATIVE : BatchKernelKind.SCALAR_ADAPTER;
            assert registry.batchKernelKind(name) == expectedKernel
                    : name + " batch kernel kind=" + registry.batchKernelKind(name);
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

    private static void testInitialOperatorNativeBatchEquivalence() {
        // 保留原生 Batch 的 4 个算子：显式 NATIVE 内核与 Single 逐行等价
        List<NativeBatchCase> nativeCases = List.of(
                new NativeBatchCase("find_indices", List.of(
                        batchRow(List.of("a", "b", "a"), "a"),
                        batchRow(List.of("x", "y", "x"), "y"))),
                new NativeBatchCase("count_distinct", List.of(
                        batchRow(List.of("a", "b", "a")),
                        batchRow(List.of("x", "x", "x")))),
                new NativeBatchCase("zip_concat", List.of(
                        batchRow(List.of("a", "b"), List.of("1", "2")),
                        batchRow(List.of("x", "y"), List.of("3", "4")))),
                new NativeBatchCase("calc_delta_seq", List.of(
                        batchRow(List.of(2.0, 5.0), 10.0),
                        batchRow(List.of(10.0, 8.0), 5.0))));
        // 移除原生 Batch 的 4 个算子：注册路由自动走标量适配器，仍须与 Single 等价
        List<NativeBatchCase> adapterCases = List.of(
                new NativeBatchCase("discrete", List.of(
                        batchRow(16.0, List.of(0, 10, 100)),
                        batchRow(150.0, List.of(0, 10, 100)))),
                new NativeBatchCase("log_base", List.of(
                        batchRow(8.0, 2.0, 1024.0),
                        batchRow(32.0, 2.0, 1024.0))),
                new NativeBatchCase("slice_by_indices", List.of(
                        batchRow(List.of("a", "b", "c"), List.of(0, 2)),
                        batchRow(List.of("x", "y", "z"), List.of(1, 2)))),
                new NativeBatchCase("get_seq_length", List.of(
                        batchRow(List.of("a", "b")),
                        batchRow(List.of("x", "y", "z")))));

        OperatorRegistry registry = OperatorRegistry.standard();
        for (NativeBatchCase batchCase : nativeCases) {
            assert registry.batchKernelKind(batchCase.operatorName())
                    == BatchKernelKind.NATIVE : batchCase.operatorName();
            BatchOperatorCall call = batchCall(batchCase.rows(), BatchDomain.OFFLINE_ROW);
            BatchOperatorResult result = registry.evaluateBatch(
                    batchCase.operatorName(), call, BatchKernelKind.NATIVE);
            assertEquivalentToSingle(registry, batchCase, result);
        }
        for (NativeBatchCase batchCase : adapterCases) {
            assert registry.batchKernelKind(batchCase.operatorName())
                    == BatchKernelKind.SCALAR_ADAPTER : batchCase.operatorName();
            BatchOperatorCall call = batchCall(batchCase.rows(), BatchDomain.OFFLINE_ROW);
            BatchOperatorResult result = registry.evaluateBatch(batchCase.operatorName(), call);
            assertEquivalentToSingle(registry, batchCase, result);
        }
    }

    private static void assertEquivalentToSingle(
            OperatorRegistry registry,
            NativeBatchCase batchCase,
            BatchOperatorResult result) {
        assert result.values().size() == batchCase.rows().size();
        for (int row = 0; row < batchCase.rows().size(); row++) {
            Object expected = registry.evaluate(
                    batchCase.operatorName(), batchCase.rows().get(row));
            assert expected.equals(result.values().valueAt(row))
                    : batchCase.operatorName() + " row=" + row;
        }
    }

    private static void testNativeBatchFailureRow() {
        OperatorRegistry registry = OperatorRegistry.standard();
        // calc_delta_seq 保留原生 Batch，第 2 行 base 非有限数触发失败行定位
        BatchOperatorCall call = batchCall(List.of(
                batchRow(List.of(2.0, 5.0), 10.0),
                batchRow(List.of(1.0), Double.NaN),
                batchRow(List.of(10.0, 8.0), 5.0)), BatchDomain.OFFLINE_ROW);
        BatchOperatorEvaluationException failure = expectThrows(
                BatchOperatorEvaluationException.class,
                () -> registry.evaluateBatch(
                        "calc_delta_seq", call, BatchKernelKind.NATIVE));
        assert failure.rowIndex() == 1 : failure.rowIndex();
        assert failure.getMessage().contains("finite") : failure.getMessage();
    }

    private static void testFindIndicesNativeBatchReusesSequenceScan() {
        CountingList<String> sequence = new CountingList<>(List.of("a", "b", "a", "c"));
        BatchOperatorCall call = batchCall(List.of(
                batchRow(sequence, "a"),
                batchRow(sequence, "b"),
                batchRow(sequence, "a"),
                batchRow(sequence, "c")), BatchDomain.ONLINE_CANDIDATE);

        BatchOperatorResult result = OperatorRegistry.standard().evaluateBatch(
                "find_indices", call, BatchKernelKind.NATIVE);
        List<List<Integer>> expected = List.of(
                List.of(0, 2), List.of(1), List.of(0, 2), List.of(3));
        for (int row = 0; row < expected.size(); row++) {
            assert expected.get(row).equals(result.values().valueAt(row)) : row;
        }
        assert sequence.getCount() == sequence.size() * 2
                : "Expected one scan per group, getCount=" + sequence.getCount();
    }

    private static BatchOperatorCall batchCall(
            List<List<Object>> rows,
            BatchDomain domain) {
        int argumentCount = rows.get(0).size();
        List<BatchColumn> columns = new ArrayList<>();
        for (int argument = 0; argument < argumentCount; argument++) {
            List<Object> values = new ArrayList<>();
            for (List<Object> row : rows) values.add(row.get(argument));
            columns.add(new ListBatchColumn(values));
        }
        return new BatchOperatorCall(
                new FixedBatchLayout(domain, rows.size()), columns);
    }

    private static List<Object> batchRow(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
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
                new OperatorCase("zip_concat", "zip_concat(str_seq, str_seq)",
                        DataType.STRING, ValueShape.SEQUENCE),
                new OperatorCase("calc_delta_seq", "calc_delta_seq(num_seq, a)",
                        DataType.DOUBLE, ValueShape.SEQUENCE));

        OperatorRegistry registry = OperatorRegistry.standard();
        ExpressionParser parser = new ExpressionParser();
        List<FeatureDefinition> definitions = new ArrayList<>(List.of(
                FeatureDefinition.raw("a", DataType.DOUBLE, EntityScope.ITEM, 1.0),
                FeatureDefinition.raw("seq", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                FeatureDefinition.builder()
                        .name("str_seq")
                        .role(com.example.featuredag.definition.FeatureRole.RAW)
                        .dataType(DataType.STRING)
                        .addEntityScope(EntityScope.USER)
                        .sourceBinding("str_seq")
                        .declaredValueShape(ValueShape.SEQUENCE)
                        .build(),
                FeatureDefinition.builder()
                        .name("num_seq")
                        .role(com.example.featuredag.definition.FeatureRole.RAW)
                        .dataType(DataType.DOUBLE)
                        .addEntityScope(EntityScope.USER)
                        .sourceBinding("num_seq")
                        .declaredValueShape(ValueShape.SEQUENCE)
                        .build()));
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

    private static void testNativeBatchPerformanceDemo() {
        try {
            Class<?> demo = Class.forName(
                    "com.example.featuredag.demo.NativeBatchPerformanceDemo");
            demo.getMethod("runSmokeTest").invoke(null);
        } catch (ClassNotFoundException error) {
            throw new AssertionError("Native Batch performance demo is missing", error);
        } catch (ReflectiveOperationException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError(
                    "Native Batch performance demo failed",
                    cause == null ? error : cause);
        }
    }

    private static void testOperatorBatchComparisonDemo() {
        try {
            Class<?> demo = Class.forName(
                    "com.example.featuredag.demo.OperatorBatchComparisonDemo");
            demo.getMethod("runSmokeTest").invoke(null);
        } catch (ClassNotFoundException error) {
            throw new AssertionError("Operator Batch comparison demo is missing", error);
        } catch (ReflectiveOperationException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError(
                    "Operator Batch comparison demo failed",
                    cause == null ? error : cause);
        }
    }

    private static void testFeatureExpressionBatchComparisonDemo() {
        try {
            Class<?> demo = Class.forName(
                    "com.example.featuredag.demo.FeatureExpressionBatchComparisonDemo");
            demo.getMethod("runSmokeTest").invoke(null);
        } catch (ClassNotFoundException error) {
            throw new AssertionError(
                    "Feature expression batch comparison demo is missing", error);
        } catch (ReflectiveOperationException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError(
                    "Feature expression batch comparison demo failed",
                    cause == null ? error : cause);
        }
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

    private record NativeBatchCase(
            String operatorName,
            List<List<Object>> rows) {}

    private record FixedBatchLayout(
            BatchDomain domain,
            int rowCount) implements BatchLayout {
        @Override
        public int groupIndexAt(int rowIndex) {
            return domain == BatchDomain.ONLINE_CANDIDATE ? rowIndex / 2 : -1;
        }

        @Override
        public int indexInGroupAt(int rowIndex) {
            return domain == BatchDomain.ONLINE_CANDIDATE ? rowIndex % 2 : rowIndex;
        }
    }

    private static final class CountingList<E> extends AbstractList<E> {
        private final List<E> values;
        private int getCount;

        private CountingList(List<E> values) {
            this.values = values;
        }

        @Override
        public E get(int index) {
            getCount++;
            return values.get(index);
        }

        @Override
        public int size() {
            return values.size();
        }

        private int getCount() {
            return getCount;
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
