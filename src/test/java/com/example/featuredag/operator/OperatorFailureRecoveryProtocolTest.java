package com.example.featuredag.operator;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.ValueShape;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class OperatorFailureRecoveryProtocolTest {
    @Test
    public void recoveringSingleCapturesKernelRuntimeException() {
        OperatorRegistry registry = new OperatorRegistry().register(new ConditionalFailOperator());

        OperatorEvaluationResult success =
                registry.evaluateRecovering("conditional_fail", List.<Object>of("a"));
        assertFalse(success.failed());
        assertEquals("ok:a", success.value());
        assertNull(success.failure());

        OperatorEvaluationResult failure =
                registry.evaluateRecovering("conditional_fail", List.<Object>of("bad"));
        assertTrue(failure.failed());
        assertNull(failure.value());
        assertEquals("bad input", failure.failure().getMessage());
    }

    @Test
    public void recoveringSingleDoesNotCatchProtocolErrorsOrJvmErrors() {
        OperatorRegistry registry = new OperatorRegistry()
                .register(new ConditionalFailOperator())
                .register(new ErrorOperator());

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluateRecovering("conditional_fail", List.of()));
        assertThrows(
                AssertionError.class,
                () -> registry.evaluateRecovering("error", List.<Object>of("a")));
    }

    @Test
    public void recoveringBatchKeepsHealthyRowsAndRecordsEachFailure() {
        OperatorRegistry registry = new OperatorRegistry().register(new ConditionalFailOperator());
        BatchOperatorCall call = new BatchOperatorCall(
                new FixedBatchLayout(BatchDomain.OFFLINE_ROW, 4),
                List.<BatchColumn>of(new ListBatchColumn(
                        Arrays.<Object>asList("a", "bad", "c", "bad"))));

        BatchOperatorResult result = registry.evaluateBatchRecovering(
                "conditional_fail",
                call,
                BatchKernelKind.SCALAR_ADAPTER);

        assertEquals(Arrays.<Object>asList("ok:a", null, "ok:c", null),
                ((ListBatchColumn) result.values()).values());
        assertEquals(Set.of(1, 3), result.rowFailures().keySet());
        assertEquals("bad input", result.rowFailures().get(1).getMessage());
        assertEquals("bad input", result.rowFailures().get(3).getMessage());
    }

    @Test
    public void directCallsRemainFailFast() {
        OperatorRegistry registry = new OperatorRegistry().register(new ConditionalFailOperator());
        IllegalArgumentException singleFailure = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("conditional_fail", List.<Object>of("bad")));
        assertEquals("bad input", singleFailure.getMessage());

        BatchOperatorCall call = new BatchOperatorCall(
                new FixedBatchLayout(BatchDomain.OFFLINE_ROW, 3),
                List.<BatchColumn>of(new ListBatchColumn(
                        Arrays.<Object>asList("bad", "b", "bad"))));
        BatchOperatorEvaluationException batchFailure = assertThrows(
                BatchOperatorEvaluationException.class,
                () -> registry.evaluateBatch(
                        "conditional_fail",
                        call,
                        BatchKernelKind.SCALAR_ADAPTER));
        assertEquals(0, batchFailure.rowIndex());
        assertEquals("bad input", batchFailure.getCause().getMessage());
    }

    private static final class ConditionalFailOperator implements OperatorDefinition {
        @Override
        public String name() {
            return "conditional_fail";
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
            if ("bad".equals(arguments.get(0))) {
                throw new IllegalArgumentException("bad input");
            }
            return "ok:" + arguments.get(0);
        }
    }

    private static final class ErrorOperator implements OperatorDefinition {
        @Override
        public String name() {
            return "error";
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
            throw new AssertionError("fatal");
        }
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
}
