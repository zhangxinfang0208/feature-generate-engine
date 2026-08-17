package com.example.featuredag.operator.builtin;

import com.example.featuredag.operator.OperatorDefinition;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 显式标准算子清单：算子实例只在此装配，推断和求值逻辑均位于独立实现类。 */
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
                new GroupCountConcatOperator(),
                new CalculateDeltaSequenceOperator(),
                new ToIntOperator(),
                new ToBigintOperator(),
                new MinOperator(),
                new MaxOperator(),
                new AddOperator(),
                new SubOperator(),
                new MulOperator(),
                new DivOperator()));
    }
}
