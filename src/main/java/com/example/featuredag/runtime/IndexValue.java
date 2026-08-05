package com.example.featuredag.runtime;

import com.example.featuredag.logical.ValueShape;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class IndexValue implements ValueHandle {
    private final Map<String, int[]> positionsByKey;

    public IndexValue(Map<String, int[]> positionsByKey) {
        this.positionsByKey = Collections.unmodifiableMap(new LinkedHashMap<>(positionsByKey));
    }

    public int count(String key) {
        int[] positions = positionsByKey.get(key);
        return positions == null ? 0 : positions.length;
    }

    public int[] positions(String key) {
        int[] positions = positionsByKey.get(key);
        return positions == null ? new int[0] : java.util.Arrays.copyOf(positions, positions.length);
    }

    @Override public ValueShape shape() { return ValueShape.INDEX; }
    @Override public Object raw() { return this; }
}
