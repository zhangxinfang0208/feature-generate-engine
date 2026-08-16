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

/**
 * 按元素计算差值，输出长度和顺序与输入序列完全一致。
 *
 * <p>两参数调用保持 {@code sequence[i] - base} 的兼容语义；可选第三个配置对象支持通过
 * {@code direction} 调整减法方向，并通过 {@code divisor} 对差值做单位换算。
 */
public final class CalculateDeltaSequenceOperator extends AbstractBuiltinOperator
        implements BatchOperatorKernel {
    private static final String DIRECTION_KEY = "direction";
    private static final String DIVISOR_KEY = "divisor";

    public CalculateDeltaSequenceOperator() {
        super("calc_delta_seq", 2, 3, true, false);
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
        DeltaOptions options = arguments.size() == 3
                ? DeltaOptions.from(arguments.get(2))
                : DeltaOptions.defaults();
        return calculate(arguments.get(0), arguments.get(1), options);
    }

    @Override
    public BatchOperatorResult evaluateBatch(BatchOperatorCall call) {
        List<Object> result = new ArrayList<Object>(call.rowCount());
        // 同一请求组内若序列对象与 base 都相同，只计算一次；追加结果时仍严格保持 Batch 行顺序。
        Map<DeltaBatchKey, Object> values = new LinkedHashMap<DeltaBatchKey, Object>();
        for (int rowIndex = 0; rowIndex < call.rowCount(); rowIndex++) {
            try {
                Object sequence = call.arguments().get(0).valueAt(rowIndex);
                double base = OperatorSupport.finiteDouble(
                        call.arguments().get(1).valueAt(rowIndex), "calc_delta_seq base");
                DeltaOptions options = call.arguments().size() == 3
                        ? DeltaOptions.from(call.arguments().get(2).valueAt(rowIndex))
                        : DeltaOptions.defaults();
                DeltaBatchKey key = new DeltaBatchKey(
                        call.layout().groupIndexAt(rowIndex), sequence, base, options);
                Object value = values.get(key);
                if (value == null) {
                    value = calculateWithBase(sequence, base, options);
                    values.put(key, value);
                }
                result.add(value);
            } catch (RuntimeException error) {
                throw OperatorSupport.batchFailure(rowIndex, error);
            }
        }
        return new BatchOperatorResult(ListBatchColumn.owned(result));
    }

    private List<Double> calculate(
            Object rawSequence,
            Object rawBase,
            DeltaOptions options) {
        double base = OperatorSupport.finiteDouble(rawBase, "calc_delta_seq base");
        return calculateWithBase(rawSequence, base, options);
    }

    private List<Double> calculateWithBase(
            Object rawSequence,
            double base,
            DeltaOptions options) {
        List<?> sequence = OperatorSupport.asList(rawSequence, name(), "sequence");
        List<Double> result = new ArrayList<Double>(sequence.size());
        // 每个元素都要求是有限数，防止 NaN/Infinity 悄悄传播到后续特征。
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
            double delta = options.direction() == DeltaDirection.BASE_MINUS_ELEMENT
                    ? base - value
                    : value - base;
            double converted = delta / options.divisor();
            if (!Double.isFinite(converted)) {
                throw new IllegalArgumentException(
                        "calc_delta_seq result at index " + index + " must be finite");
            }
            result.add(converted);
        }
        return OperatorSupport.immutableList(result);
    }

    private enum DeltaDirection {
        ELEMENT_MINUS_BASE,
        BASE_MINUS_ELEMENT;

        private static DeltaDirection parse(Object value) {
            if (!(value instanceof String)) {
                throw new IllegalArgumentException(
                        "calc_delta_seq direction must be a string");
            }
            try {
                return DeltaDirection.valueOf((String) value);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException(
                        "calc_delta_seq direction must be ELEMENT_MINUS_BASE"
                                + " or BASE_MINUS_ELEMENT, got: " + value);
            }
        }
    }

    private static final class DeltaOptions {
        private static final DeltaOptions DEFAULTS = new DeltaOptions(
                DeltaDirection.ELEMENT_MINUS_BASE, 1.0);

        private final DeltaDirection direction;
        private final double divisor;

        private DeltaOptions(DeltaDirection direction, double divisor) {
            this.direction = direction;
            this.divisor = divisor;
        }

        private static DeltaOptions defaults() {
            return DEFAULTS;
        }

        private static DeltaOptions from(Object rawConfig) {
            if (!(rawConfig instanceof Map<?, ?>)) {
                throw new IllegalArgumentException(
                        "calc_delta_seq config must be an object");
            }
            Map<?, ?> config = (Map<?, ?>) rawConfig;
            for (Object key : config.keySet()) {
                if (!DIRECTION_KEY.equals(key) && !DIVISOR_KEY.equals(key)) {
                    throw new IllegalArgumentException(
                            "calc_delta_seq config contains unknown key: " + key);
                }
            }

            DeltaDirection direction = config.containsKey(DIRECTION_KEY)
                    ? DeltaDirection.parse(config.get(DIRECTION_KEY))
                    : DeltaDirection.ELEMENT_MINUS_BASE;
            double divisor = config.containsKey(DIVISOR_KEY)
                    ? OperatorSupport.finiteDouble(
                            config.get(DIVISOR_KEY), "calc_delta_seq divisor")
                    : 1.0;
            if (divisor <= 0.0) {
                throw new IllegalArgumentException(
                        "calc_delta_seq divisor must be greater than 0");
            }
            if (direction == DeltaDirection.ELEMENT_MINUS_BASE && divisor == 1.0) {
                return DEFAULTS;
            }
            return new DeltaOptions(direction, divisor);
        }

        private DeltaDirection direction() {
            return direction;
        }

        private double divisor() {
            return divisor;
        }
    }

    private static final class DeltaBatchKey {
        private final int groupIndex;
        private final Object sequence;
        private final long baseBits;
        private final DeltaDirection direction;
        private final long divisorBits;

        private DeltaBatchKey(
                int groupIndex,
                Object sequence,
                double base,
                DeltaOptions options) {
            this.groupIndex = groupIndex;
            // 序列按对象身份比较，避免对长序列做深比较，也防止不同视图因内容相同而误复用。
            this.sequence = sequence;
            this.baseBits = Double.doubleToLongBits(base);
            this.direction = options.direction();
            this.divisorBits = Double.doubleToLongBits(options.divisor());
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof DeltaBatchKey)) return false;
            DeltaBatchKey other = (DeltaBatchKey) value;
            return groupIndex == other.groupIndex
                    && sequence == other.sequence
                    && baseBits == other.baseBits
                    && direction == other.direction
                    && divisorBits == other.divisorBits;
        }

        @Override
        public int hashCode() {
            int hash = 31 * groupIndex + System.identityHashCode(sequence);
            hash = 31 * hash + Long.hashCode(baseBits);
            hash = 31 * hash + direction.hashCode();
            return 31 * hash + Long.hashCode(divisorBits);
        }
    }
}
