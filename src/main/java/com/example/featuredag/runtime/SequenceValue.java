package com.example.featuredag.runtime;

import com.example.featuredag.logical.ValueShape;

public sealed interface SequenceValue extends ValueHandle permits SequenceBlock, SequenceView {
    int size();
    SequenceBlock baseBlock();
    int baseIndexAt(int logicalIndex);

    default SequenceEvent eventAt(int logicalIndex) {
        return baseBlock().eventAtBaseIndex(baseIndexAt(logicalIndex));
    }

    @Override default ValueShape shape() { return ValueShape.SEQUENCE; }
    @Override default Object raw() { return this; }
}
