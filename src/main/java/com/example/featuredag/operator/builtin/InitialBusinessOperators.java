package com.example.featuredag.operator.builtin;

import com.example.featuredag.operator.OperatorDefinition;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 显式标准算子清单：首期 8 个业务算子，加上 to_int / to_bigint / min / max 数值算子与 add / sub / mul / div 算术算子。 */
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
