package com.example.featuredag.runtime;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

public final class BitmapSelection implements SequenceSelection {
    private final int[] baseIndices;

    public BitmapSelection(BitSet bitmap) {
        List<Integer> values = new ArrayList<>();
        for (int index = bitmap.nextSetBit(0); index >= 0; index = bitmap.nextSetBit(index + 1)) {
            values.add(index);
        }
        this.baseIndices = values.stream().mapToInt(Integer::intValue).toArray();
    }

    @Override public int size() { return baseIndices.length; }
    @Override public int baseIndexAt(int logicalIndex) { return baseIndices[logicalIndex]; }
}
