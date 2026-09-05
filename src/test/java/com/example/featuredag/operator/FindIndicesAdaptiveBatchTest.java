package com.example.featuredag.operator;

import com.example.featuredag.runtime.SequenceBlock;
import com.example.featuredag.runtime.SequenceView;
import org.junit.Test;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class FindIndicesAdaptiveBatchTest {
    private final OperatorRegistry registry = OperatorRegistry.standard();

    @Test
    public void independentSequencesDoNotHashEveryElement() {
        AtomicInteger hashes = new AtomicInteger();
        List<Object> first = values(hashes);
        List<Object> second = values(hashes);
        BatchOperatorResult result = evaluate(List.of(first, second),
                List.of(new HashedValue(37, hashes), new HashedValue(99, hashes)), 2);
        assertEquals(List.of(37), result.values().valueAt(0));
        assertEquals(List.of(99), result.values().valueAt(1));
        assertEquals("One-off queries should scan without building a full hash index", 0, hashes.get());
    }

    @Test
    public void oneQueryInEachGroupDoesNotTriggerCrossGroupIndexing() {
        AtomicInteger hashes = new AtomicInteger();
        List<Object> sequence = values(hashes);
        BatchOperatorResult result = evaluate(List.of(sequence, sequence),
                List.of(new HashedValue(1, hashes), new HashedValue(2, hashes)), 1);
        assertEquals(List.of(1), result.values().valueAt(0));
        assertEquals(List.of(2), result.values().valueAt(1));
        assertEquals(0, hashes.get());
    }

    @Test
    public void repeatedSequenceBuildsAnIndexAndPreservesSingleResults() {
        CountingList sequence = new CountingList(Arrays.asList("a", null, "a", "b"));
        List<Object> targets = Arrays.asList("a", null, "missing", "b", "a");
        BatchOperatorResult result = evaluate(java.util.Collections.nCopies(targets.size(), sequence),
                targets, targets.size());
        assertTrue("Repeated queries should not repeatedly traverse the sequence",
                sequence.reads <= 2 * sequence.size());
        for (int row = 0; row < targets.size(); row++) {
            assertEquals(registry.evaluate("find_indices", Arrays.asList(sequence, targets.get(row))),
                    result.values().valueAt(row));
            List<?> positions = (List<?>) result.values().valueAt(row);
            assertThrows(UnsupportedOperationException.class, () -> positions.add(null));
        }
        assertTrue(result.rowFailures().isEmpty());
    }

    @Test
    public void invalidRowsRemainIsolatedWhileRepeatedRowsUseTheIndex() {
        List<?> sequence = Arrays.asList("a", null, "a");
        BatchOperatorResult result = evaluate(Arrays.asList(sequence, "bad", sequence, sequence),
                Arrays.asList("a", "a", null, "absent"), 4);
        assertEquals(List.of(0, 2), result.values().valueAt(0));
        assertEquals(Set.of(1), result.rowFailures().keySet());
        assertEquals(List.of(1), result.values().valueAt(2));
        assertEquals(List.of(), result.values().valueAt(3));
    }

    @Test
    public void concreteViewsKeepLogicalPositionsAndSeparateIndexes() {
        Map<String, Object> a = Map.of("key", "a");
        Map<String, Object> b = Map.of("key", "b");
        SequenceBlock block = new SequenceBlock("adaptive-view", 1L, List.of(a, b, a));
        SequenceView first = SequenceView.filterByColumn(block, "key", "a");
        SequenceView second = SequenceView.filterByColumn(block, "key", "b");
        BatchOperatorResult result = evaluate(List.of(first, second, first, second),
                List.of(a, b, a, a), 4);
        assertEquals(List.of(0, 1), result.values().valueAt(0));
        assertEquals(List.of(0), result.values().valueAt(1));
        assertEquals(List.of(0, 1), result.values().valueAt(2));
        assertEquals(List.of(), result.values().valueAt(3));
    }

    private BatchOperatorResult evaluate(List<?> sequences, List<?> targets, int groupSize) {
        BatchLayout layout = new BatchLayout() {
            public BatchDomain domain() { return BatchDomain.ONLINE_CANDIDATE; }
            public int rowCount() { return sequences.size(); }
            public int groupIndexAt(int row) { return row / groupSize; }
            public int indexInGroupAt(int row) { return row % groupSize; }
        };
        return registry.evaluateBatchRecovering("find_indices", new BatchOperatorCall(layout,
                List.of(new ListBatchColumn(sequences), new ListBatchColumn(targets))), BatchKernelKind.NATIVE);
    }

    private static List<Object> values(AtomicInteger hashes) {
        List<Object> result = new ArrayList<>();
        for (int i = 0; i < 100; i++) result.add(new HashedValue(i, hashes));
        return result;
    }

    private record HashedValue(int value, AtomicInteger hashes) {
        @Override public int hashCode() { hashes.incrementAndGet(); return value; }
        @Override public boolean equals(Object other) {
            return other instanceof HashedValue element && element.value == value;
        }
    }

    private static final class CountingList extends AbstractList<Object> {
        private final List<?> values;
        private int reads;
        private CountingList(List<?> values) { this.values = values; }
        @Override public int size() { return values.size(); }
        @Override public Object get(int index) { reads++; return values.get(index); }
    }
}
