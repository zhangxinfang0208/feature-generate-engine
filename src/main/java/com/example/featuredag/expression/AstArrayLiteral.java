package com.example.featuredag.expression;

import java.util.List;
import java.util.Objects;

public record AstArrayLiteral(List<AstNode> elements, SourceSpan sourceSpan) implements AstNode {
    public AstArrayLiteral {
        Objects.requireNonNull(elements, "elements");
        Objects.requireNonNull(sourceSpan, "sourceSpan");
        elements = List.copyOf(elements);
    }
}
