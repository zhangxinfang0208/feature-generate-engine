package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.BatchOperatorCall;
import com.example.featuredag.operator.BatchOperatorKernel;
import com.example.featuredag.operator.BatchOperatorResult;
import com.example.featuredag.operator.ListBatchColumn;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
public final class CalculateDeltaSequenceOperator extends AbstractBuiltinOperator
        implements BatchOperatorKernel {
    public CalculateDeltaSequenceOperator() {
        super("calc_delta_seq", 2, 2, true, false);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        // 事件序列不做隐式数值投影：构图期即拒绝 EVENT_SEQUENCE，运行时元素检查为防御。
        if (inputs.get(0).outputType() == DataType.EVENT_SEQUENCE) {
            throw new IllegalArgumentException(
                    "calc_delta_seq requires numeric elements; event sequences are not"
                            + " supported (no implicit value projection)");
        }
        return OperatorSupport.fixedInference(inputs, DataType.DOUBLE, ValueShape.SEQUENCE);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        return calculate(arguments.get(0), arguments.get(1));
    }

    @Override
    public BatchOperatorResult evaluateBatch(BatchOperatorCall call) {
        List<Object> result = new ArrayList<Object>(call.rowCount());
        Map<DeltaBatchKey, Object> values = new LinkedHashMap<DeltaBatchKey, Object>();
        for (int rowIndex = 0; rowIndex < call.rowCount(); rowIndex++) {
            try {
                Object sequence = call.arguments().get(0).valueAt(rowIndex);
                double base = OperatorSupport.finiteDouble(
                        call.arguments().get(1).valueAt(rowIndex), "calc_delta_seq base");
                DeltaBatchKey key = new DeltaBatchKey(
                        call.layout().groupIndexAt(rowIndex), sequence, base);
                Object value = values.get(key);
                if (value == null) {
                    value = calculateWithBase(sequence, base);
                    values.put(key, value);
                }
                result.add(value);
            } catch (RuntimeException error) {
                throw OperatorSupport.batchFailure(rowIndex, error);
            }
        }
        return new BatchOperatorResult(new ListBatchColumn(result));
    }

    private List<Double> calculate(Object rawSequence, Object rawBase) {
        double base = OperatorSupport.finiteDouble(rawBase, "calc_delta_seq base");
        return calculateWithBase(rawSequence, base);
    }

    private List<Double> calculateWithBase(Object rawSequence, double base) {
        List<?> sequence = OperatorSupport.asList(rawSequence, name(), "sequence");
        List<Double> result = new ArrayList<Double>(sequence.size());
        for (int index = 0; index < sequence.size(); index++) {
            Object element = sequence.get(index);
            if (element instanceof Map<?, ?>) {
                // 事件序列不做隐式数值投影：事件元素需先显式投影为数值列后才能参与差值计算。
                throw new IllegalArgumentException(
                        "calc_delta_seq requires numeric elements; event sequences are not"
                                + " supported (no implicit value projection)");
            }
            double value = OperatorSupport.finiteDouble(
                    element, "calc_delta_seq element at index " + index);
            result.add(value - base);
        }
        return OperatorSupport.immutableList(result);
    }

    private static final class DeltaBatchKey {
        private final int groupIndex;
        private final Object sequence;
        private final long baseBits;

        private DeltaBatchKey(int groupIndex, Object sequence, double base) {
            this.groupIndex = groupIndex;
            this.sequence = sequence;
            this.baseBits = Double.doubleToLongBits(base);
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof DeltaBatchKey)) return false;
            DeltaBatchKey other = (DeltaBatchKey) value;
            return groupIndex == other.groupIndex
                    && sequence == other.sequence
                    && baseBits == other.baseBits;
        }

        @Override
        public int hashCode() {
            int hash = 31 * groupIndex + System.identityHashCode(sequence);
            return 31 * hash + Long.hashCode(baseBits);
        }
    }
}
