package com.example.featuredag.runtime;

public record RangeSelection(int startInclusive, int endExclusive) implements SequenceSelection {
    public RangeSelection {
        if (startInclusive < 0 || endExclusive < startInclusive) {
            throw new IllegalArgumentException("Invalid range " + startInclusive + ".." + endExclusive);
        }
    }

    @Override public int size() { return endExclusive - startInclusive; }

    @Override public int baseIndexAt(int logicalIndex) {
        if (logicalIndex < 0 || logicalIndex >= size()) throw new IndexOutOfBoundsException(logicalIndex);
        return startInclusive + logicalIndex;
    }
}
