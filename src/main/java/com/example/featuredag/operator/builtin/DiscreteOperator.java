package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * discrete：数值分桶。
 *
 * <p>不提供原生 BatchOperatorKernel：批内可复用的只有边界→BigDecimal 转换，
 * 每行仍需 bucket 比较，复用收益不足以覆盖批开销（实测 batch 劣化），
 * 由 SingleLoopBatchOperatorKernel 逐行适配，结果与 Single 完全一致。
 */
public final class DiscreteOperator extends AbstractBuiltinOperator {
    public DiscreteOperator() {
        super("discrete", 2, 2, true, false);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.fixedInference(inputs, DataType.INT, ValueShape.SCALAR);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        return bucket(toValue(arguments.get(0)), toBoundaries(arguments.get(1)));
    }

    private static BigDecimal toValue(Object value) {
        return OperatorSupport.asPreciseDecimal(
                OperatorSupport.asNumber(value),
                "discrete requires a finite numeric value");
    }

    private List<BigDecimal> toBoundaries(Object value) {
        List<?> values = OperatorSupport.asList(value, name(), "discrete_key");
        List<BigDecimal> boundaries = new ArrayList<BigDecimal>(values.size());
        BigDecimal previous = null;
        for (int index = 0; index < values.size(); index++) {
            Object boundary = values.get(index);
            if (!(boundary instanceof Number)) {
                throw new IllegalArgumentException(
                        "discrete boundary at index " + index + " is not numeric: " + boundary);
            }
            BigDecimal current = OperatorSupport.asPreciseDecimal(
                    (Number) boundary,
                    "discrete boundary at index " + index + " must be finite");
            if (previous != null && current.compareTo(previous) <= 0) {
                throw new IllegalArgumentException(
                        "discrete boundaries must be strictly increasing at index " + index);
            }
            boundaries.add(current);
            previous = current;
        }
        return OperatorSupport.immutableList(boundaries);
    }

    private static int bucket(BigDecimal value, List<BigDecimal> boundaries) {
        int bucket = 0;
        for (BigDecimal boundary : boundaries) {
            if (value.compareTo(boundary) >= 0) bucket++;
        }
        return bucket;
    }
}
