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

    private ListBatchColumn(List<Object> ownedValues, boolean trusted) {
        this.values = Collections.unmodifiableList(ownedValues);
    }

    /**
     * 包内信任构造：跳过防御拷贝。调用方必须保证传入的列表是刚构建、后续不再持有
     * 可变引用的独占列表；公开构造器仍保留拷贝语义，对外不可变契约不变。
     */
    public static ListBatchColumn owned(List<Object> values) {
        return new ListBatchColumn(Objects.requireNonNull(values, "values"), true);
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
