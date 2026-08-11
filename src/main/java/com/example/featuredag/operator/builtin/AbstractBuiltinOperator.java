package com.example.featuredag.operator.builtin;

import com.example.featuredag.operator.OperatorDefinition;
import com.example.featuredag.operator.OperatorSemantic;

import java.util.List;
import java.util.Objects;

/** Shared immutable metadata for one built-in operator definition. */
public abstract class AbstractBuiltinOperator implements OperatorDefinition {
    private final String name;
    private final int minArguments;
    private final int maxArguments;
    private final boolean deterministic;
    private final boolean parameterized;
    private final boolean supportsSequenceView;
    private final long estimatedCost;
    private final List<OperatorSemantic> semantics;

    protected AbstractBuiltinOperator(
            String name,
            int minArguments,
            int maxArguments,
            boolean deterministic,
            boolean parameterized,
            boolean supportsSequenceView) {
        this(name, minArguments, maxArguments, deterministic, parameterized,
                supportsSequenceView, 1L, List.of());
    }

    protected AbstractBuiltinOperator(
            String name,
            int minArguments,
            int maxArguments,
            boolean deterministic,
            boolean parameterized,
            boolean supportsSequenceView,
            long estimatedCost,
            List<OperatorSemantic> semantics) {
        this.name = Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        if (minArguments < 0 || maxArguments < minArguments) {
            throw new IllegalArgumentException("invalid argument range for operator " + name);
        }
        if (estimatedCost < 0) {
            throw new IllegalArgumentException("estimatedCost must not be negative");
        }
        this.minArguments = minArguments;
        this.maxArguments = maxArguments;
        this.deterministic = deterministic;
        this.parameterized = parameterized;
        this.supportsSequenceView = supportsSequenceView;
        this.estimatedCost = estimatedCost;
        this.semantics = List.copyOf(semantics);
    }

    @Override public final String name() { return name; }
    @Override public final int minArguments() { return minArguments; }
    @Override public final int maxArguments() { return maxArguments; }
    @Override public final boolean deterministic() { return deterministic; }
    @Override public final boolean parameterized() { return parameterized; }
    @Override public final boolean supportsSequenceView() { return supportsSequenceView; }
    @Override public final long estimatedCost() { return estimatedCost; }
    @Override public final List<OperatorSemantic> semantics() { return semantics; }
}
