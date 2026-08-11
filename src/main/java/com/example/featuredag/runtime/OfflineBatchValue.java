package com.example.featuredag.runtime;

import com.example.featuredag.definition.ValueShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 离线批值句柄：一个元素对应输入批中的一行，elementShape 描述单行值形状。
 */
public final class OfflineBatchValue implements ValueHandle {
    private final List<Object> values;
    private final ValueShape elementShape;

    public OfflineBatchValue(List<?> values, ValueShape elementShape) {
        Objects.requireNonNull(values, "values");
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
        this.elementShape = Objects.requireNonNull(elementShape, "elementShape");
    }

    public List<Object> values() { return values; }
    public ValueShape elementShape() { return elementShape; }
    public int size() { return values.size(); }
    public Object valueAt(int index) { return values.get(index); }

    @Override public ValueShape shape() { return elementShape; }
    @Override public Object raw() { return values; }
}
