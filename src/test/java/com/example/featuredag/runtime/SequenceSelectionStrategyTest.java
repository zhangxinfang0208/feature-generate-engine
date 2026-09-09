package com.example.featuredag.runtime;

import org.junit.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SequenceSelectionStrategyTest {
    @Test
    public void denseNonContiguousFilterAndSliceUseIndexSelection() {
        SequenceBlock block = block(true, false, true, true);
        SequenceView filtered = SequenceView.filterByColumn(block, "keep", true);
        assertTrue(filtered.selection() instanceof IndexSelection);
        assertIndices(filtered, 0, 2, 3);
        SequenceView sliced = SequenceView.slice(filtered, 0, 3);
        assertTrue(sliced.selection() instanceof IndexSelection);
        assertIndices(sliced, 0, 2, 3);
        assertSame(block, sliced.baseBlock());
    }

    @Test
    public void contiguousSparseAndEmptySelectionsRetainTheirSemantics() {
        SequenceBlock block = block(false, true, true, true, false, false);
        SequenceView contiguous = SequenceView.filterByColumn(block, "keep", true);
        assertTrue(contiguous.selection() instanceof RangeSelection);
        assertIndices(contiguous, 1, 2, 3);
        assertIndices(SequenceView.slice(contiguous, 1, 3), 2, 3);
        SequenceView sparse = SequenceView.filterByColumn(
                block(true, false, false, false, true, false), "keep", true);
        assertTrue(sparse.selection() instanceof IndexSelection);
        assertIndices(sparse, 0, 4);
        assertIndices(SequenceView.filterByColumn(block(false, false), "keep", true));
        assertIndices(SequenceView.slice(contiguous, 1, 1));
    }

    @Test
    public void denseIndexViewsPreserveOrderAndDuplicatesThroughFilterAndSlice() {
        SequenceBlock block = block(true, true, true, true);
        SequenceView source = new SequenceView(block, new IndexSelection(new int[] {3, 0, 3}));
        assertIndices(SequenceView.filterByColumn(source, "keep", true), 3, 0, 3);
        assertIndices(SequenceView.slice(source, 0, 3), 3, 0, 3);
    }

    @Test
    public void explicitlyConstructedBitmapRemainsSupportedAndDefensivelyCopied() {
        BitSet bits = new BitSet();
        bits.set(0);
        bits.set(2);
        bits.set(3);
        SequenceView view = new SequenceView(block(true, true, true, true), new BitmapSelection(bits));
        bits.clear();
        assertIndices(view, 0, 2, 3);
        assertIndices(SequenceView.filterByColumn(view, "keep", true), 0, 2, 3);
    }

    private static SequenceBlock block(boolean... flags) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (boolean flag : flags) rows.add(Map.of("keep", flag));
        return new SequenceBlock("selection-strategy", 0, rows);
    }

    private static void assertIndices(SequenceValue view, int... expected) {
        assertEquals(expected.length, view.size());
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], view.baseIndexAt(i));
    }
}
