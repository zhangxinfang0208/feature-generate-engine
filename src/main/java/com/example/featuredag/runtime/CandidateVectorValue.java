package com.example.featuredag.runtime;

import com.example.featuredag.definition.ValueShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 候选向量值句柄：一个元素对应一个候选（item），
 * 在线推理时算子按候选下标逐元素求值（见 DagRuntime.vectorizedApply）。
 */
public record CandidateVectorValue(List<Object> values) implements ValueHandle {
    public CandidateVectorValue {
        values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    @Override public ValueShape shape() { return ValueShape.CANDIDATE_VECTOR; }
    @Override public Object raw() { return values; }
    public int size() { return values.size(); }
    public Object valueAt(int index) { return values.get(index); }
}
