package com.example.featuredag.runtime;

public sealed interface SequenceSelection permits RangeSelection, IndexSelection, BitmapSelection {
    int size();
    int baseIndexAt(int logicalIndex);
}
