package com.example.featuredag.runtime;

import com.example.featuredag.logical.ValueShape;

/** 标量值句柄：包装单个普通对象（Number/String/Boolean/Map 等）。 */
public record ScalarValue(Object value) implements ValueHandle {
    @Override public ValueShape shape() { return ValueShape.SCALAR; }
    @Override public Object raw() { return value; }
}
