package com.example.featuredag.expression;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record AstCall(
        String functionName,
        List<AstNode> arguments,
        Map<Integer, String> argumentNames,
        int invocationCount,
        SourceSpan sourceSpan) implements AstNode {
    public AstCall(String functionName, List<AstNode> arguments, SourceSpan sourceSpan) {
        this(functionName, arguments, Map.of(), 1, sourceSpan);
    }

    public AstCall(
            String functionName,
            List<AstNode> arguments,
            int invocationCount,
            SourceSpan sourceSpan) {
        this(functionName, arguments, Map.of(), invocationCount, sourceSpan);
    }

    public AstCall {
        Objects.requireNonNull(functionName, "functionName");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(argumentNames, "argumentNames");
        Objects.requireNonNull(sourceSpan, "sourceSpan");
        if (invocationCount < 1) {
            throw new IllegalArgumentException("invocationCount must be positive");
        }
        arguments = List.copyOf(arguments);
        argumentNames = Map.copyOf(argumentNames);
        for (Map.Entry<Integer, String> entry : argumentNames.entrySet()) {
            int argumentIndex = entry.getKey();
            if (argumentIndex < 0 || argumentIndex >= arguments.size()) {
                throw new IllegalArgumentException(
                        "Named argument index out of range: " + argumentIndex);
            }
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                throw new IllegalArgumentException("Named argument must not be blank");
            }
        }
    }

    public String argumentName(int argumentIndex) {
        return argumentNames.get(argumentIndex);
    }

    public boolean hasNamedArguments() {
        return !argumentNames.isEmpty();
    }
}
