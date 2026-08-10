package com.example.featuredag.runtime;

import com.example.featuredag.logical.ValueShape;

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
