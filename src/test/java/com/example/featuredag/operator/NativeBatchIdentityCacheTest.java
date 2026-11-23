package com.example.featuredag.operator;

import com.example.featuredag.operator.builtin.CountDistinctOperator;
import com.example.featuredag.operator.builtin.FindIndicesOperator;
import org.junit.Test;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * 原生 Batch 的批内复用键改为按（请求组，对象身份）的零分配缓存后，
 * 复用边界必须与原来一致：同组同身份才复用，不同组、不同对象互不影响，负的组下标（离线行）同样可用。
 */
public class NativeBatchIdentityCacheTest {

    @Test
    public void findIndicesAndCountDistinctReuseOnlyWithinAGroupAndIdentity() {
        List<String> sequence = Arrays.asList("a", "b", "a", "c");
        List<String> other = Arrays.asList("a", "a", "d");
        Object[][] findRows = {
                {sequence, "a"}, {sequence, "b"}, {sequence, "a"},
                {sequence, "c"}, {other, "a"}, {sequence, "a"}};
        int[] groups = {-1, -1, 0, 0, 0, 2};
        verify(new FindIndicesOperator(), findRows, groups);

        Object[][] countRows = {
                {sequence}, {sequence}, {sequence}, {other}, {other}, {sequence}, {sequence}};
        verify(new CountDistinctOperator(), countRows, new int[] {-1, -1, 0, 0, 2, 2, -1});
    }

    @Test
    public void adaptiveScanCountsAreUnchanged() {
        // 与存量自测同一场景：2 组 × 2 行共用一个序列。首次出现直接扫描，同组再次出现才建索引，
        // 因此每个组各读取两遍序列；之后同组同身份的行直接命中，不再读取。
        CountingList<String> sequence = new CountingList<>(Arrays.asList("a", "b", "a", "c"));
        BatchOperatorCall call = call(
                new int[] {0, 0, 1, 1, 1, 1},
                column(sequence, sequence, sequence, sequence, sequence, sequence),
                column("a", "b", "a", "c", "b", "a"));

        BatchOperatorResult result = new FindIndicesOperator().evaluateBatch(call);

        assertEquals(Arrays.asList(0, 2), result.values().valueAt(0));
        assertEquals(Arrays.asList(1), result.values().valueAt(1));
        assertEquals(Arrays.asList(0, 2), result.values().valueAt(2));
        assertEquals(Arrays.asList(3), result.values().valueAt(3));
        assertEquals(Arrays.asList(1), result.values().valueAt(4));
        assertEquals(Arrays.asList(0, 2), result.values().valueAt(5));
        assertFalse(result.hasFailures());
        assertEquals(sequence.size() * 4, sequence.getCount());
    }

    @Test
    public void equalButDistinctObjectsInOneGroupAreScannedSeparately() {
        CountingList<String> first = new CountingList<>(Arrays.asList("a", "b"));
        CountingList<String> second = new CountingList<>(Arrays.asList("a", "b"));
        assertEquals(first, second);
        // 上面的内容比较本身会读取元素，清零后只统计 Kernel 的扫描次数。
        first.reset();
        second.reset();

        BatchOperatorResult result = new CountDistinctOperator().evaluateBatch(call(
                new int[] {0, 0, 0, 0},
                column(first, first, second, second)));

        assertEquals(2, result.values().valueAt(0));
        assertEquals(2, result.values().valueAt(2));
        // 内容相等但身份不同：各自扫描一次，不会因为 equals 相同而合并请求状态。
        assertEquals(first.size(), first.getCount());
        assertEquals(second.size(), second.getCount());
    }

    @Test
    public void aFailureInOneRowDoesNotPoisonTheCache() {
        BatchOperatorResult result = new FindIndicesOperator().evaluateBatch(call(
                new int[] {0, 0, 0},
                column(Arrays.asList("a", "b"), Arrays.asList("a", "b"), Arrays.asList("a", "b")),
                column("a", null, "b")));

        // null 目标在 Single 路径同样失败；该行记为失败，前后两行照常命中或扫描。
        assertEquals(Arrays.asList(0), result.values().valueAt(0));
        assertEquals(Arrays.asList(1), result.values().valueAt(2));
    }

    private static void verify(OperatorDefinition operator, Object[][] rows, int[] groups) {
        BatchColumn[] columns = new BatchColumn[rows[0].length];
        for (int column = 0; column < columns.length; column++) {
            Object[] values = new Object[rows.length];
            for (int row = 0; row < rows.length; row++) values[row] = rows[row][column];
            columns[column] = column(values);
        }
        BatchOperatorResult batch = ((BatchOperatorKernel) operator).evaluateBatch(call(groups, columns));
        for (int row = 0; row < rows.length; row++) {
            assertEquals(operator.name() + " row " + row,
                    operator.evaluate(new ArrayList<>(Arrays.asList(rows[row]))),
                    batch.values().valueAt(row));
        }
    }

    private static BatchOperatorCall call(int[] groups, BatchColumn... columns) {
        return new BatchOperatorCall(new BatchLayout() {
            public BatchDomain domain() { return BatchDomain.ONLINE_CANDIDATE; }
            public int rowCount() { return groups.length; }
            public int groupIndexAt(int row) { return groups[row]; }
            public int indexInGroupAt(int row) { return row; }
        }, Arrays.asList(columns));
    }

    @SafeVarargs
    private static BatchColumn column(Object... values) {
        return new ListBatchColumn(Arrays.asList(values));
    }

    private static final class CountingList<E> extends AbstractList<E> {
        private final List<E> values;
        private int getCount;

        CountingList(List<E> values) {
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

        int getCount() {
            return getCount;
        }

        void reset() {
            getCount = 0;
        }
    }
}
