package com.example.featuredag.operator;

import com.example.featuredag.operator.builtin.*;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class BorrowedArgumentsBatchTest {
    private static BatchOperatorCall call(int rows, BatchColumn... columns) {
        return new BatchOperatorCall(new BatchLayout() {
            public BatchDomain domain() { return BatchDomain.ONLINE_CANDIDATE; }
            public int rowCount() { return rows; }
            public int groupIndexAt(int row) { return row / 2; }
            public int indexInGroupAt(int row) { return row % 2; }
        }, Arrays.asList(columns));
    }

    private static BatchColumn column(Object... values) {
        return new ListBatchColumn(Arrays.asList(values));
    }

    @Test public void ordinaryKernelsMayRetainMutateAndReturnArguments() {
        List<List<Object>> retained = new ArrayList<>();
        SingleOperatorKernel kernel = args -> {
            retained.add(args);
            args.add("extra");
            return args;
        };
        BatchOperatorResult result = new SingleLoopBatchOperatorKernel(kernel)
                .evaluateBatch(call(3, column(1, 2, 3)));
        for (int row = 0; row < 3; row++) {
            assertEquals(Arrays.asList(row + 1, "extra"), retained.get(row));
            assertSame(retained.get(row), result.values().valueAt(row));
        }
        assertNotSame(retained.get(0), retained.get(1));
        assertFalse(BorrowedArgumentsKernel.class.isAssignableFrom(AbstractBuiltinOperator.class));
    }

    @Test public void borrowedArgumentsAreReadOnlyAndReadColumnsEagerlyInOrder() {
        List<String> reads = new ArrayList<>();
        BatchColumn left = new BatchColumn() {
            public int size() { return 3; }
            public Object valueAt(int row) { reads.add("left" + row); return row; }
        };
        BatchColumn right = new BatchColumn() {
            public int size() { return 3; }
            public Object valueAt(int row) { reads.add("right" + row); return row == 1 ? null : row + 10; }
        };
        BorrowedArgumentsKernel kernel = args -> {
            int row = (Integer) args.get(0);
            assertEquals(Arrays.asList("left" + row, "right" + row),
                    reads.subList(reads.size() - 2, reads.size()));
            assertThrows(UnsupportedOperationException.class, () -> args.set(0, 99));
            if (args.get(1) == null) throw new IllegalArgumentException("row failure");
            return new ArrayList<>(args);
        };
        SingleLoopBatchOperatorKernel adapter = new SingleLoopBatchOperatorKernel(kernel);
        BatchOperatorResult result = adapter.evaluateBatch(call(3, left, right));
        assertEquals(Arrays.asList(0, 10), result.values().valueAt(0));
        assertEquals(Arrays.asList(2, 12), result.values().valueAt(2));
        assertNull(result.values().valueAt(1));
        assertEquals(Collections.singleton(1), result.rowFailures().keySet());
        assertEquals(6, reads.size());
        assertEquals(BatchKernelKind.SCALAR_ADAPTER, adapter.batchKernelKind());
    }

    @Test public void columnFailuresEscapeAndEmptyBatchesNeverEvaluate() {
        RuntimeException failure = new IllegalStateException("column");
        BatchColumn bad = new BatchColumn() {
            public int size() { return 1; }
            public Object valueAt(int row) { throw failure; }
        };
        BorrowedArgumentsKernel kernel = args -> { fail("must not evaluate"); return null; };
        SingleLoopBatchOperatorKernel adapter = new SingleLoopBatchOperatorKernel(kernel);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> adapter.evaluateBatch(call(1, bad))));
        assertEquals(0, adapter.evaluateBatch(call(0)).values().size());
        BorrowedArgumentsKernel zeroArity = args -> args.size();
        assertEquals(0, new SingleLoopBatchOperatorKernel(zeroArity)
                .evaluateBatch(call(1)).values().valueAt(0));
    }

    @Test public void concurrentCallsKeepTheirOwnContainers() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        BorrowedArgumentsKernel kernel = args -> {
            Object before = args.get(0);
            try { barrier.await(5, TimeUnit.SECONDS); }
            catch (Exception e) { throw new AssertionError(e); }
            assertEquals(before, args.get(0));
            return before;
        };
        SingleLoopBatchOperatorKernel adapter = new SingleLoopBatchOperatorKernel(kernel);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<BatchOperatorResult> first = pool.submit(() -> adapter.evaluateBatch(call(3, column(1, 2, 3))));
            Future<BatchOperatorResult> second = pool.submit(() -> adapter.evaluateBatch(call(3, column(4, 5, 6))));
            assertEquals(3, first.get(10, TimeUnit.SECONDS).values().valueAt(2));
            assertEquals(6, second.get(10, TimeUnit.SECONDS).values().valueAt(2));
        } finally { pool.shutdownNow(); }
    }

    @Test public void reentrantCallsDoNotOverwriteOuterArguments() {
        SingleLoopBatchOperatorKernel[] adapter = new SingleLoopBatchOperatorKernel[1];
        BorrowedArgumentsKernel kernel = args -> {
            int value = (Integer) args.get(0);
            if (value == 1) {
                assertEquals(9, adapter[0].evaluateBatch(call(1, column(9))).values().valueAt(0));
                assertEquals(1, args.get(0));
            }
            return value;
        };
        adapter[0] = new SingleLoopBatchOperatorKernel(kernel);
        assertEquals(1, adapter[0].evaluateBatch(call(1, column(1))).values().valueAt(0));
    }

    @Test public void optedInBuiltinsMatchSingleIncludingFailuresAndSequences() {
        verify(new AddOperator(), new Object[][]{
            {1, 2}, {Long.MAX_VALUE, 1}, {null, 2}, {Arrays.asList(1, 2), 3},
            {Arrays.asList(1, 2), Collections.singletonList(1)}, {1.5, 2}});
        verify(new MulOperator(), new Object[][]{
            {2, 3}, {Long.MAX_VALUE, 2}, {null, 3}, {Arrays.asList(1, 2), 4},
            {Arrays.asList(1, 2), Arrays.asList(3, 4)}, {1.5, 2}});
        verify(new SliceByIndicesOperator(), new Object[][]{
            {Arrays.asList("a", null, Arrays.asList(1)), Arrays.asList(2, 0, 2)},
            {Arrays.asList(1, 2), Arrays.asList(1, 0)}, {null, Collections.emptyList()},
            {Arrays.asList(1), Arrays.asList(-1)}, {Arrays.asList(1), Arrays.asList(0.5)},
            {Collections.emptyList(), Collections.emptyList()}});
        OperatorSequence sequence = new OperatorSequence() {
            public int size() { return 2; }
            public Object elementAt(int i) { return i + 1; }
            public OperatorSequence filterByColumn(String c, Object v) { throw new UnsupportedOperationException(); }
        };
        verify(new GetSequenceLengthOperator(), new Object[][]{
            {Arrays.asList(1, 2)}, {new int[]{1, 2, 3}}, {null}, {sequence}, {Collections.emptyList()}});
        verify(new AddOperator(), new Object[][]{{sequence, 2}});
        verify(new SliceByIndicesOperator(), new Object[][]{{sequence, Arrays.asList(1, 0)}});
    }

    private static void verify(OperatorDefinition operator, Object[][] rows) {
        assertTrue(operator instanceof BorrowedArgumentsKernel);
        BatchColumn[] columns = new BatchColumn[rows[0].length];
        for (int col = 0; col < columns.length; col++) {
            List<Object> values = new ArrayList<>();
            for (Object[] row : rows) values.add(row[col]);
            columns[col] = new ListBatchColumn(values);
        }
        BatchOperatorResult batch = new SingleLoopBatchOperatorKernel(operator)
                .evaluateBatch(call(rows.length, columns));
        for (int row = 0; row < rows.length; row++) {
            Object expected;
            try { expected = operator.evaluate(new ArrayList<>(Arrays.asList(rows[row]))); }
            catch (RuntimeException error) {
                assertNotNull(batch.rowFailures().get(row));
                assertEquals(error.getClass(), batch.rowFailures().get(row).getClass());
                assertEquals(error.getMessage(), batch.rowFailures().get(row).getMessage());
                continue;
            }
            assertFalse(batch.rowFailures().containsKey(row));
            assertEquals(expected, batch.values().valueAt(row));
        }
    }
}
