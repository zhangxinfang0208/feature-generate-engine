package com.example.featuredag.runtime;

import com.example.featuredag.definition.ValueShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 候选向量值句柄：一个元素对应一个候选（item），
 * 在线推理时算子按候选下标逐元素求值（见 DagRuntime.vectorizedApply）。
 */
public final class CandidateVectorValue implements ValueHandle {
    private final List<Object> values;

    public CandidateVectorValue(List<Object> values) {
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    private CandidateVectorValue(List<Object> ownedValues, boolean trusted) {
        this.values = Collections.unmodifiableList(ownedValues);
    }

    /**
     * 包内信任构造：跳过防御复制。调用方必须保证传入的列表是刚构建、后续不再持有
     * 可变引用的独占列表；公开构造器仍保留拷贝语义，对外不可变契约不变。
     */
    static CandidateVectorValue owned(List<Object> values) {
        return new CandidateVectorValue(Objects.requireNonNull(values, "values"), true);
    }

    @Override public ValueShape shape() { return ValueShape.CANDIDATE_VECTOR; }
    @Override public Object raw() { return values; }
    public List<Object> values() { return values; }
    public int size() { return values.size(); }
    public Object valueAt(int index) { return values.get(index); }

    // 保持改为 class 之前的 record 语义：按元素内容比较。
    @Override
    public boolean equals(Object other) {
        return other instanceof CandidateVectorValue vector && values.equals(vector.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "CandidateVectorValue[values=" + values + "]";
    }
}
