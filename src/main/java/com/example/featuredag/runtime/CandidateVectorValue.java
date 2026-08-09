package com.example.featuredag.runtime;

import com.example.featuredag.logical.ValueShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record CandidateVectorValue(List<Object> values) implements ValueHandle {
    public CandidateVectorValue {
        values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    @Override public ValueShape shape() { return ValueShape.CANDIDATE_VECTOR; }
    @Override public Object raw() { return values; }
    public int size() { return values.size(); }
    public Object valueAt(int index) { return values.get(index); }
}
