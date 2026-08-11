package com.example.featuredag.operator;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.ValueShape;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public record OperatorInference(
        DataType outputType,
        Set<EntityScope> entityScopes,
        ValueShape valueShape) {
    public OperatorInference {
        entityScopes = Collections.unmodifiableSet(new LinkedHashSet<>(entityScopes));
    }
}
