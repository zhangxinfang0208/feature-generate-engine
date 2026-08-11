package com.example.featuredag.runtime;

import com.example.featuredag.definition.ValueShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 序列值句柄（列表承载）：外部 List 序列的轻量包装，
 * 携带 alignmentId 作为序列对齐标识；底层列式序列由 SequenceBlock/SequenceView 承载。
 */
public final class ListSequenceValue implements ValueHandle {
    private final String alignmentId;
    private final List<Object> values;

    public ListSequenceValue(String alignmentId, List<?> values) {
        this.alignmentId = requireText(alignmentId, "alignmentId");
        Objects.requireNonNull(values, "values");
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    public String alignmentId() { return alignmentId; }
    public int size() { return values.size(); }
    public List<Object> values() { return values; }
    @Override public ValueShape shape() { return ValueShape.SEQUENCE; }
    @Override public Object raw() { return values; }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String result = value.trim();
        if (result.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return result;
    }
}
