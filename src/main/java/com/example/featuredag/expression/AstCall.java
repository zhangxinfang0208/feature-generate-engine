package com.example.featuredag.expression;

import java.util.List;
import java.util.Objects;

public record AstCall(
        String functionName,
        List<AstNode> arguments,
        int invocationCount,
        SourceSpan sourceSpan) implements AstNode {
    public AstCall(String functionName, List<AstNode> arguments, SourceSpan sourceSpan) {
        this(functionName, arguments, 1, sourceSpan);
    }

    public AstCall {
        Objects.requireNonNull(functionName, "functionName");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(sourceSpan, "sourceSpan");
        if (invocationCount < 1) {
            throw new IllegalArgumentException("invocationCount must be positive");
        }
        arguments = List.copyOf(arguments);
    }
}
