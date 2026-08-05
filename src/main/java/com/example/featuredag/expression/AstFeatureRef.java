package com.example.featuredag.expression;

import java.util.Objects;

public record AstFeatureRef(String featureName, SourceSpan sourceSpan) implements AstNode {
    public AstFeatureRef {
        Objects.requireNonNull(featureName, "featureName");
        Objects.requireNonNull(sourceSpan, "sourceSpan");
    }
}
