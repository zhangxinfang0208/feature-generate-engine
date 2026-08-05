package com.example.featuredag.runtime;

import com.example.featuredag.logical.ValueShape;

public record ScalarValue(Object value) implements ValueHandle {
    @Override public ValueShape shape() { return ValueShape.SCALAR; }
    @Override public Object raw() { return value; }
}
