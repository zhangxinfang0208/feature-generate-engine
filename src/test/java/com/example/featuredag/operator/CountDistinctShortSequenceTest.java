package com.example.featuredag.operator;

import com.example.featuredag.operator.builtin.CountDistinctOperator;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** count_distinct 的短序列线性去重与长序列哈希去重结果一致，覆盖各种序列载体和长度边界。 */
public class CountDistinctShortSequenceTest {

    @Test
    public void shortAndLongSequencesCountTheSameAcrossCarrierKinds() {
        CountDistinctOperator operator = new CountDistinctOperator();
        // 0、1、5、16 走线性扫描；17、40 走哈希集合。第 3 个位置放 null，确认 null 也按元素计数。
        int[] lengths = {0, 1, 5, 16, 17, 40};
        for (int length : lengths) {
            List<Object> elements = new ArrayList<>();
            for (int index = 0; index < length; index++) {
                elements.add(index == 3 ? null : Integer.valueOf(index % 7));
            }
            int expected = new HashSet<>(elements).size();
            assertEquals(expected, operator.evaluate(List.of(new ArrayList<>(elements))));
            assertEquals(expected, operator.evaluate(List.of(Collections.unmodifiableList(elements))));
            assertEquals(expected, operator.evaluate(List.of(new LinkedList<>(elements))));
            assertEquals(expected, operator.evaluate(List.of(new LinkedHashSet<>(elements))));
            assertEquals(expected, operator.evaluate(Collections.singletonList(elements.toArray())));
            assertEquals(expected, operator.evaluate(List.of(sequence(elements))));
        }
    }

    @Test
    public void equalValuesOfDifferentBoxTypesAreNotMerged() {
        CountDistinctOperator operator = new CountDistinctOperator();

        assertEquals(3, operator.evaluate(List.of(Arrays.asList(1, 1L, 1.0))));
        assertEquals(1, operator.evaluate(List.of(Arrays.asList("a", new StringBuilder("a").toString(), "a"))));
        assertEquals(1, operator.evaluate(List.of(Arrays.asList(200L, 200L, 200L))));
        assertEquals(3, operator.evaluate(List.of(Arrays.asList(200, 200, 201, 202))));
    }

    @Test
    public void batchRowsDoNotShareElementsThroughTheScratchBuffer() {
        BatchOperatorResult result = new CountDistinctOperator().evaluateBatch(new BatchOperatorCall(
                new BatchLayout() {
                    public BatchDomain domain() { return BatchDomain.ONLINE_CANDIDATE; }
                    public int rowCount() { return 5; }
                    public int groupIndexAt(int row) { return 0; }
                    public int indexInGroupAt(int row) { return row; }
                },
                List.of(new ListBatchColumn(Arrays.asList(
                        Arrays.asList(1, 1, 2),
                        Arrays.asList(3, 4, 5, 3, null, null),
                        Arrays.asList("a", "a", "a"),
                        Collections.emptyList(),
                        Arrays.asList(7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 7, 8))))));

        assertEquals(2, result.values().valueAt(0));
        assertEquals(4, result.values().valueAt(1));
        assertEquals(1, result.values().valueAt(2));
        assertEquals(0, result.values().valueAt(3));
        assertEquals(10, result.values().valueAt(4));
    }

    @Test
    public void nonSequencesAreStillRejectedWithTheSameMessage() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new CountDistinctOperator().evaluate(List.of(5)));

        assertEquals("count_distinct expects a sequence, got: java.lang.Integer", error.getMessage());
    }

    private static OperatorSequence sequence(List<Object> elements) {
        List<Object> copy = new ArrayList<>(elements);
        return new OperatorSequence() {
            public int size() { return copy.size(); }
            public Object elementAt(int index) { return copy.get(index); }
            public OperatorSequence filterByColumn(String column, Object value) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
