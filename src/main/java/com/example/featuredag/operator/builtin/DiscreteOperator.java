package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * discrete：数值标量或序列分桶，序列元素共享同一组边界。
 *
 * <p>序列输入按逻辑下标逐元素分桶，边界只解析一次；标量调用保持原有行为。
 * 不提供原生 BatchOperatorKernel：批内可复用的只有边界→BigDecimal 转换，
 * 每行仍需 bucket 比较，复用收益不足以覆盖批开销（实测 batch 劣化），
 * 由 SingleLoopBatchOperatorKernel 逐行适配，结果与 Single 完全一致。
 */
public final class DiscreteOperator extends AbstractBuiltinOperator {
    public DiscreteOperator() {
        super("discrete", 2, 2, true, true);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        OperatorInputMetadata value = inputs.get(0);
        boolean unknownLiteralSequence = value.outputType() == DataType.OBJECT
                && value.valueShape() == ValueShape.SEQUENCE;
        if (!value.outputType().isNumeric() && !unknownLiteralSequence) {
            throw new IllegalArgumentException(
                    "discrete requires numeric value input, got: " + value.outputType());
        }
        ValueShape outputShape;
        if (value.valueShape() == ValueShape.SEQUENCE) {
            outputShape = ValueShape.SEQUENCE;
        } else if (value.valueShape() == ValueShape.SCALAR
                || value.valueShape() == ValueShape.CANDIDATE_VECTOR) {
            outputShape = ValueShape.SCALAR;
        } else {
            throw new IllegalArgumentException(
                    "discrete does not support value shape: " + value.valueShape());
        }
        OperatorInputMetadata boundaries = inputs.get(1);
        if (boundaries.valueShape() != ValueShape.SEQUENCE) {
            throw new IllegalArgumentException(
                    "discrete boundaries must be a sequence, got: "
                            + boundaries.valueShape());
        }
        if (!boundaries.outputType().isNumeric()
                && boundaries.outputType() != DataType.OBJECT) {
            throw new IllegalArgumentException(
                    "discrete requires numeric boundaries, got: "
                            + boundaries.outputType());
        }
        return OperatorSupport.fixedInference(inputs, DataType.INT, outputShape);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        Object value = arguments.get(0);
        List<BigDecimal> boundaries = toBoundaries(arguments.get(1));
        if (OperatorSupport.isSequence(value)) {
            return OperatorSupport.mapSequence(
                    value,
                    name(),
                    element -> Integer.valueOf(bucket(toValue(element), boundaries)));
        }
        return Integer.valueOf(bucket(toValue(value), boundaries));
    }

    private static BigDecimal toValue(Object value) {
        return OperatorSupport.asPreciseDecimal(
                OperatorSupport.asNumber(value),
                "discrete requires a finite numeric value");
    }

    private List<BigDecimal> toBoundaries(Object value) {
        int size = OperatorSupport.sequenceSize(value, name(), "discrete_key");
        List<BigDecimal> boundaries = new ArrayList<BigDecimal>(size);
        BigDecimal previous = null;
        // BigDecimal 保留输入数值精度，避免 double 舍入影响临界点归桶结果。
        for (int index = 0; index < size; index++) {
            Object boundary = OperatorSupport.sequenceElementAt(
                    value, index, name(), "discrete_key");
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
        // 相等时进入右侧桶，因此边界 [10, 20] 对应 (-∞,10)、[10,20)、[20,+∞) 三个桶。
        for (BigDecimal boundary : boundaries) {
            if (value.compareTo(boundary) >= 0) bucket++;
        }
        return bucket;
    }
}
