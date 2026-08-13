package com.example.featuredag.runtime;

import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorSequence;

import java.util.Map;

/**
 * 序列值契约：逻辑下标（logicalIndex）经 baseIndexAt 映射到底层块下标（baseIndex），
 * 从而支持 SequenceView 的零拷贝过滤/切片（Selection 只记录下标，不复制数据）。
 */
public sealed interface SequenceValue extends ValueHandle, OperatorSequence
        permits SequenceBlock, SequenceView {
    int size();
    SequenceBlock baseBlock();
    int baseIndexAt(int logicalIndex);

    @Override default Map<String, Object> elementAt(int index) {
        return baseBlock().rowAtBaseIndex(baseIndexAt(index));
    }

    @Override default SequenceValue filterByColumn(String column, Object value) {
        return SequenceView.filterByColumn(this, column, value);
    }

    @Override default ValueShape shape() { return ValueShape.SEQUENCE; }
    @Override default Object raw() { return this; }
}
