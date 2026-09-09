package com.example.featuredag.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Zero-copy sequence view. Every view points directly to the base block; view
 * chains are normalized when a new view is created.
 *
 * 零拷贝序列视图：不复制数据，只记录 baseIndex 选择（Selection）；
 * 过滤/切片产生的下标连续时使用 Range，其余使用 Index，避免经位图往返转换。
 */
public final class SequenceView implements SequenceValue {
    private final SequenceBlock baseBlock;
    private final SequenceSelection selection;

    public SequenceView(SequenceBlock baseBlock, SequenceSelection selection) {
        this.baseBlock = Objects.requireNonNull(baseBlock, "baseBlock");
        this.selection = Objects.requireNonNull(selection, "selection");
    }

    public static SequenceView filterByColumn(
            SequenceValue source,
            String column,
            Object value) {
        Objects.requireNonNull(column, "column");
        List<Integer> selected = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            int baseIndex = source.baseIndexAt(i);
            if (Objects.equals(source.baseBlock().columnValueAt(column, baseIndex), value)) {
                selected.add(baseIndex);
            }
        }
        int[] indices = selected.stream().mapToInt(Integer::intValue).toArray();
        return new SequenceView(source.baseBlock(), chooseSelection(indices));
    }

    public static SequenceView slice(SequenceValue source, int startInclusive, int endExclusive) {
        int start = Math.max(0, startInclusive);
        int end = Math.min(source.size(), endExclusive);
        if (start > end) start = end;
        int[] indices = new int[end - start];
        for (int i = start; i < end; i++) indices[i - start] = source.baseIndexAt(i);
        return new SequenceView(source.baseBlock(), chooseSelection(indices));
    }

    private static SequenceSelection chooseSelection(int[] indices) {
        if (indices.length == 0) return new IndexSelection(indices);
        boolean contiguous = true;
        for (int i = 1; i < indices.length; i++) {
            if (indices[i] != indices[i - 1] + 1) {
                contiguous = false;
                break;
            }
        }
        if (contiguous) return new RangeSelection(indices[0], indices[indices.length - 1] + 1);
        // 直接保留逻辑顺序和重复位置；BitSet 会排序、去重，且最终仍转换回 int[]。
        return new IndexSelection(indices);
    }

    @Override public int size() { return selection.size(); }
    @Override public SequenceBlock baseBlock() { return baseBlock; }
    @Override public int baseIndexAt(int logicalIndex) { return selection.baseIndexAt(logicalIndex); }
    public SequenceSelection selection() { return selection; }
}
