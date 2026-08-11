package com.example.featuredag.operator.builtin;

import com.example.featuredag.operator.OperatorDefinition;

import java.util.List;

/** 棣栨湡浜や粯鐨勪笟鍔＄畻瀛愭竻鍗曘€?*/
public final class InitialBusinessOperators {
    private InitialBusinessOperators() {}

    public static List<OperatorDefinition> definitions() {
        return List.of(
                new DiscreteOperator(),
                new LogBaseOperator(),
                new SliceByIndicesOperator(),
                new FindIndicesOperator(),
                new GetSequenceLengthOperator(),
                new CountDistinctOperator(),
                new ZipConcatOperator(),
                new CalculateDeltaSequenceOperator());
    }
}
