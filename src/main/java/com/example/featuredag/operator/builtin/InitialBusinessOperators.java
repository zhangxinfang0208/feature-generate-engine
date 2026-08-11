package com.example.featuredag.operator.builtin;

import com.example.featuredag.operator.OperatorDefinition;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Manifest for the eight operators delivered in the initial release. */
public final class InitialBusinessOperators {
    private InitialBusinessOperators() {}

    public static List<OperatorDefinition> definitions() {
        return Collections.unmodifiableList(Arrays.<OperatorDefinition>asList(
                new DiscreteOperator(),
                new LogBaseOperator(),
                new SliceByIndicesOperator(),
                new FindIndicesOperator(),
                new GetSequenceLengthOperator(),
                new CountDistinctOperator(),
                new ZipConcatOperator(),
                new CalculateDeltaSequenceOperator()));
    }
}
