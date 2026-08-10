package com.example.featuredag.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Zero-copy sequence view. Every view points directly to the base block; view
 * chains are normalized when a new view is created.
 *
 * 零拷贝序列视图：不复制数据，只记录 baseIndex 选择（Selection）；
 * 过滤/切片产生的下标按密度选择最优表示——连续→Range、稀疏→Index、密集→Bitmap。
 */
public final class SequenceView implements SequenceValue {
    private final SequenceBlock baseBlock;
    private final SequenceSelection selection;

    public SequenceView(SequenceBlock baseBlock, SequenceSelection selection) {
        this.baseBlock = Objects.requireNonNull(baseBlock, "baseBlock");
        this.selection = Objects.requireNonNull(selection, "selection");
    }

    public static SequenceView filterByIndustry(SequenceValue source, String industry) {
        List<Integer> selected = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            int baseIndex = source.baseIndexAt(i);
            if (Objects.equals(source.baseBlock().industryAtBaseIndex(baseIndex), industry)) {
                selected.add(baseIndex);
            }
        }
        int[] indices = selected.stream().mapToInt(Integer::intValue).toArray();
        return new SequenceView(source.baseBlock(), chooseSelection(indices, source.baseBlock().size()));
    }

    public static SequenceView slice(SequenceValue source, int startInclusive, int endExclusive) {
        int start = Math.max(0, startInclusive);
        int end = Math.min(source.size(), endExclusive);
        if (start > end) start = end;
        int[] indices = new int[end - start];
        for (int i = start; i < end; i++) indices[i - start] = source.baseIndexAt(i);
        return new SequenceView(source.baseBlock(), chooseSelection(indices, source.baseBlock().size()));
    }

    private static SequenceSelection chooseSelection(int[] indices, int baseSize) {
        if (indices.length == 0) return new IndexSelection(indices);
        boolean contiguous = true;
        for (int i = 1; i < indices.length; i++) {
            if (indices[i] != indices[i - 1] + 1) {
                contiguous = false;
                break;
            }
        }
        if (contiguous) return new RangeSelection(indices[0], indices[indices.length - 1] + 1);
        if (indices.length * 2 < baseSize) return new IndexSelection(indices);
        java.util.BitSet bitmap = new java.util.BitSet(baseSize);
        for (int index : indices) bitmap.set(index);
        return new BitmapSelection(bitmap);
    }

    @Override public int size() { return selection.size(); }
    @Override public SequenceBlock baseBlock() { return baseBlock; }
    @Override public int baseIndexAt(int logicalIndex) { return selection.baseIndexAt(logicalIndex); }
    public SequenceSelection selection() { return selection; }
}
