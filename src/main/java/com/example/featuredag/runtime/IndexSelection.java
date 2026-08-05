package com.example.featuredag.runtime;

import java.util.Arrays;

public final class IndexSelection implements SequenceSelection {
    private final int[] baseIndices;

    public IndexSelection(int[] baseIndices) {
        this.baseIndices = Arrays.copyOf(baseIndices, baseIndices.length);
    }

    @Override public int size() { return baseIndices.length; }

    @Override public int baseIndexAt(int logicalIndex) {
        return baseIndices[logicalIndex];
    }

    public int[] copyIndices() { return Arrays.copyOf(baseIndices, baseIndices.length); }
}
