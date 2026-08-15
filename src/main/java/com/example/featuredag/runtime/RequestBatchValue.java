package com.example.featuredag.runtime;

import com.example.featuredag.definition.ValueShape;

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

    private RequestBatchValue(List<Object> ownedValues, ValueShape elementShape, boolean trusted) {
        this.values = Collections.unmodifiableList(ownedValues);
        this.elementShape = Objects.requireNonNull(elementShape, "elementShape");
    }

    /**
     * 包内信任构造：跳过防御拷贝。调用方必须保证传入的列表是刚构建、后续不再持有
     * 可变引用的独占列表；公开构造器仍保留拷贝语义，对外不可变契约不变。
     */
    static RequestBatchValue owned(List<Object> values, ValueShape elementShape) {
        return new RequestBatchValue(Objects.requireNonNull(values, "values"), elementShape, true);
    }

    public List<Object> values() { return values; }
    public ValueShape elementShape() { return elementShape; }
    public int size() { return values.size(); }
    public Object valueAt(int index) { return values.get(index); }

    @Override public ValueShape shape() { return elementShape; }
    @Override public Object raw() { return values; }
}
