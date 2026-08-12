package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.BatchOperatorCall;
import com.example.featuredag.operator.BatchOperatorKernel;
import com.example.featuredag.operator.BatchOperatorResult;
import com.example.featuredag.operator.ListBatchColumn;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DiscreteOperator extends AbstractBuiltinOperator
        implements BatchOperatorKernel {
    public DiscreteOperator() {
        super("discrete", 2, 2, true, true, false);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.fixedInference(inputs, DataType.INT, ValueShape.SCALAR);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        return bucket(toValue(arguments.get(0)), toBoundaries(arguments.get(1)));
    }

    @Override
    public BatchOperatorResult evaluateBatch(BatchOperatorCall call) {
        List<Object> result = new ArrayList<Object>(call.rowCount());
        Map<OperatorSupport.IdentityBatchKey, List<BigDecimal>> convertedBoundaries =
                new LinkedHashMap<OperatorSupport.IdentityBatchKey, List<BigDecimal>>();
        for (int rowIndex = 0; rowIndex < call.rowCount(); rowIndex++) {
            try {
                BigDecimal value = toValue(call.arguments().get(0).valueAt(rowIndex));
                Object rawBoundaries = call.arguments().get(1).valueAt(rowIndex);
                OperatorSupport.IdentityBatchKey key =
                        OperatorSupport.identityBatchKey(-1, rawBoundaries);
                List<BigDecimal> boundaries = convertedBoundaries.get(key);
                if (boundaries == null) {
                    boundaries = toBoundaries(rawBoundaries);
                    convertedBoundaries.put(key, boundaries);
                }
                result.add(bucket(value, boundaries));
            } catch (RuntimeException error) {
                throw OperatorSupport.batchFailure(rowIndex, error);
            }
        }
        return new BatchOperatorResult(new ListBatchColumn(result));
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
