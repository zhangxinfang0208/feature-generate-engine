package com.example.featuredag.expression;

import java.util.Objects;

public record AstLiteral(Object value, SourceSpan sourceSpan) implements AstNode {
    public AstLiteral {
        Objects.requireNonNull(sourceSpan, "sourceSpan");
    }
}
