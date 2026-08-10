package com.example.featuredag.runtime;

import com.example.featuredag.logical.ValueShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 在线请求批值：一个元素对应一个 USER/SCENE 请求分组。 */
public final class RequestBatchValue implements ValueHandle {
    private final List<Object> values;
    private final ValueShape elementShape;

    public RequestBatchValue(List<?> values, ValueShape elementShape) {
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
