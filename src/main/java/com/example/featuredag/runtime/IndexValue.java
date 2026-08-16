package com.example.featuredag.runtime;

import com.example.featuredag.definition.ValueShape;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class IndexValue implements ValueHandle {
    private final Map<Object, int[]> positionsByKey;

    public IndexValue(Map<?, int[]> positionsByKey) {
        Map<Object, int[]> copy = new LinkedHashMap<>();
        for (Map.Entry<?, int[]> entry : positionsByKey.entrySet()) {
            copy.put(entry.getKey(), java.util.Arrays.copyOf(entry.getValue(), entry.getValue().length));
        }
        this.positionsByKey = Collections.unmodifiableMap(copy);
    }

    private IndexValue(Map<Object, int[]> ownedPositionsByKey, boolean trusted) {
        this.positionsByKey = Collections.unmodifiableMap(ownedPositionsByKey);
    }

    /**
     * 包内信任构造：跳过对每个数组的防御拷贝。调用方必须保证传入的 Map 和其中每个
     * int[] 都是刚构建、后续不再持有可变引用的独占数据；公开构造器仍保留拷贝语义。
     */
    static IndexValue owned(Map<Object, int[]> positionsByKey) {
        return new IndexValue(positionsByKey, true);
    }

    public int count(Object key) {
        int[] positions = positionsByKey.get(key);
        return positions == null ? 0 : positions.length;
    }

    public int[] positions(Object key) {
        int[] positions = positionsByKey.get(key);
        return positions == null ? new int[0] : java.util.Arrays.copyOf(positions, positions.length);
    }

    @Override public ValueShape shape() { return ValueShape.INDEX; }
    @Override public Object raw() { return this; }
}
