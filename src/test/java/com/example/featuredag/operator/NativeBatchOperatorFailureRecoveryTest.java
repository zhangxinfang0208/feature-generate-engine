package com.example.featuredag.operator;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.ValueShape;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class NativeBatchOperatorFailureRecoveryTest {
    @Test
    public void recoveryRoutingUsesOnlyCapableNativeKernels() {
        OperatorRegistry registry = OperatorRegistry.standard();
        for (String operatorName : Arrays.asList(
                "find_indices", "find_indices_any", "count_distinct",
                "zip_concat", "calc_delta_seq")) {
            assertEquals(BatchKernelKind.NATIVE, registry.recoveringBatchKernelKind(operatorName));
        }

        registry.register(new LegacyNativeOperator());
        assertEquals(BatchKernelKind.NATIVE, registry.batchKernelKind("legacy_native"));
        assertEquals(
                BatchKernelKind.SCALAR_ADAPTER,
                registry.recoveringBatchKernelKind("legacy_native"));
    }

    @Test
    public void findIndicesIsolatesInvalidSequenceRow() {
        assertRecoveredRows(
                "find_indices",
                batchCall(
                        Arrays.<Object>asList(List.of("a", "b", "a"), "invalid", List.of("c")),
                        Arrays.<Object>asList("a", "a", "c")),
                List.of(0, 2),
                List.of(0));
    }

    @Test
    public void countDistinctIsolatesInvalidSequenceRow() {
        assertRecoveredRows(
                "count_distinct",
                batchCall(Arrays.<Object>asList(List.of("a", "a"), 7, List.of("c", "d"))),
                Integer.valueOf(1),
                Integer.valueOf(2));
    }

    @Test
    public void zipConcatIsolatesUnequalLengthRow() {
        assertRecoveredRows(
                "zip_concat",
                batchCall(
                        Arrays.<Object>asList(List.of("a"), List.of("bad"), List.of("c")),
                        Arrays.<Object>asList(List.of("1"), List.of("x", "y"), List.of("3"))),
                List.of("a#1"),
                List.of("c#3"));
    }

    @Test
    public void calculateDeltaSequenceIsolatesNonFiniteBaseRow() {
        assertRecoveredRows(
                "calc_delta_seq",
                batchCall(
                        Arrays.<Object>asList(List.of(2.0, 5.0), List.of(1.0), List.of(10.0, 8.0)),
                        Arrays.<Object>asList(10.0, Double.NaN, 5.0)),
                List.of(8.0, 5.0),
                List.of(-5.0, -3.0));
    }

    private static void assertRecoveredRows(
            String operatorName,
            BatchOperatorCall call,
            Object firstValue,
            Object thirdValue) {
        OperatorRegistry registry = OperatorRegistry.standard();
        assertTrue(registry.require(operatorName) instanceof RecoverableBatchOperatorKernel);

        BatchOperatorResult result = registry.evaluateBatchRecovering(
                operatorName,
                call,
                BatchKernelKind.NATIVE);

        assertEquals(firstValue, result.values().valueAt(0));
        assertNull(result.values().valueAt(1));
        assertEquals(thirdValue, result.values().valueAt(2));
        assertEquals(Set.of(1), result.rowFailures().keySet());
        assertTrue(result.rowFailures().get(1) instanceof IllegalArgumentException);
    }

    @SafeVarargs
    private static BatchOperatorCall batchCall(List<Object>... arguments) {
        List<BatchColumn> columns = new java.util.ArrayList<BatchColumn>(arguments.length);
        for (List<Object> argument : arguments) {
            columns.add(new ListBatchColumn(argument));
        }
        return new BatchOperatorCall(
                new FixedBatchLayout(BatchDomain.OFFLINE_ROW, arguments[0].size()),
                columns);
    }

    private static final class FixedBatchLayout implements BatchLayout {
        private final BatchDomain domain;
        private final int rowCount;

        private FixedBatchLayout(BatchDomain domain, int rowCount) {
            this.domain = domain;
            this.rowCount = rowCount;
        }

        @Override
        public BatchDomain domain() {
            return domain;
        }

        @Override
        public int rowCount() {
            return rowCount;
        }

        @Override
        public int groupIndexAt(int rowIndex) {
            return -1;
        }

        @Override
        public int indexInGroupAt(int rowIndex) {
            return rowIndex;
        }
    }

    private static final class LegacyNativeOperator
            implements OperatorDefinition, BatchOperatorKernel {
        @Override
        public String name() {
            return "legacy_native";
        }

        @Override
        public int minArguments() {
            return 1;
        }

        @Override
        public int maxArguments() {
            return 1;
        }

        @Override
        public boolean deterministic() {
            return true;
        }

        @Override
        public boolean supportsSequenceView() {
            return false;
        }

        @Override
        public OperatorInference infer(List<OperatorInputMetadata> inputs) {
            return new OperatorInference(DataType.STRING, Set.of(EntityScope.USER), ValueShape.SCALAR);
        }

        @Override
        public Object evaluate(List<Object> arguments) {
            return arguments.get(0);
        }

        @Override
        public BatchOperatorResult evaluateBatch(BatchOperatorCall call) {
            return new BatchOperatorResult(call.arguments().get(0));
        }
    }
}
