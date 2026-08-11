package com.example.featuredag.operator.builtin;

import com.example.featuredag.operator.OperatorDefinition;

import java.util.List;

/** Explicit manifest for the eight operators delivered in the initial release. */
public final class StandardOperators {
    private StandardOperators() {}

    public static List<OperatorDefinition> definitions() {
        return InitialBusinessOperators.definitions();
    }
}
