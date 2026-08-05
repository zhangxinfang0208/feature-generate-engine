package com.example.featuredag.expression;

import java.util.List;
import java.util.Objects;

public record AstCall(String functionName, List<AstNode> arguments, SourceSpan sourceSpan) implements AstNode {
    public AstCall {
        Objects.requireNonNull(functionName, "functionName");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(sourceSpan, "sourceSpan");
        arguments = List.copyOf(arguments);
    }
}
