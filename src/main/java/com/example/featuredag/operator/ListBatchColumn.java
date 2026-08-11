package com.example.featuredag.operator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 支持 null 元素的不可变对象列。 */
public final class ListBatchColumn implements BatchColumn {
    private final List<Object> values;

    public ListBatchColumn(List<?> values) {
        Objects.requireNonNull(values, "values");
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    public List<Object> values() {
        return values;
    }

    @Override
    public int size() {
        return values.size();
    }

    @Override
    public Object valueAt(int rowIndex) {
        return values.get(rowIndex);
    }
}
