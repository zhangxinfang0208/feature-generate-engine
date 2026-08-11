package com.example.featuredag.operator.builtin;

import com.example.featuredag.operator.OperatorDefinition;

import java.util.ArrayList;
import java.util.List;

/** Explicit standard operator manifest; registration order is stable and observable. */
public final class StandardOperators {
    private StandardOperators() {}

    public static List<OperatorDefinition> definitions() {
        List<OperatorDefinition> definitions = new ArrayList<>(List.of(
                // 寮曟搸绀轰緥涓庡叕鍏?API 濂戠害鎵€闇€鐨勫熀纭€绠楀瓙銆?
                new CoalesceOperator(),
                new NormalizeOperator(),
                new ExtractIndustryOperator(),
                new CountOperator(),
                new AddOperator(),
                new LogOperator(),
                new MultiplyOperator(),
                new FindListIndexTypedOperator(),
                new ListIndexTypedOperator(),
                new GreaterInSequenceTypedOperator(),
                new DivideByNumberOperator(),
                new RoundOperator(),
                new DivideOperator(),
                new LeastOperator()));
        definitions.addAll(InitialBusinessOperators.definitions());
        return List.copyOf(definitions);
    }
}
