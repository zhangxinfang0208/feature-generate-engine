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

public final class LogBaseOperator extends AbstractBuiltinOperator
        implements BatchOperatorKernel {
    public LogBaseOperator() {
        super("log_base", 3, 3, true, false, false);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.fixedInference(inputs, DataType.DOUBLE, ValueShape.SCALAR);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        return evaluateValues(arguments.get(0), arguments.get(1), arguments.get(2));
    }

    @Override
    public BatchOperatorResult evaluateBatch(BatchOperatorCall call) {
        List<Object> result = new ArrayList<Object>(call.rowCount());
        Map<LogParameterKey, LogParameters> parameters =
                new LinkedHashMap<LogParameterKey, LogParameters>();
        for (int rowIndex = 0; rowIndex < call.rowCount(); rowIndex++) {
            try {
                double value = OperatorSupport.finiteDouble(
                        call.arguments().get(0).valueAt(rowIndex), "log_base value");
                double base = OperatorSupport.finiteDouble(
                        call.arguments().get(1).valueAt(rowIndex), "log_base base");
                double upbound = OperatorSupport.finiteDouble(
                        call.arguments().get(2).valueAt(rowIndex), "log_base upbound");
                validateBase(base);
                validateValue(value);
                validateUpbound(upbound);
                LogParameterKey key = new LogParameterKey(base, upbound);
                LogParameters prepared = parameters.get(key);
                if (prepared == null) {
                    prepared = new LogParameters(upbound, Math.log(base));
                    parameters.put(key, prepared);
                }
                result.add(Math.log(Math.min(value, prepared.upbound))
                        / prepared.logBase);
            } catch (RuntimeException error) {
                throw OperatorSupport.batchFailure(rowIndex, error);
            }
        }
        return new BatchOperatorResult(new ListBatchColumn(result));
    }

    private static double evaluateValues(Object rawValue, Object rawBase, Object rawUpbound) {
        double value = OperatorSupport.finiteDouble(rawValue, "log_base value");
        double base = OperatorSupport.finiteDouble(rawBase, "log_base base");
        double upbound = OperatorSupport.finiteDouble(rawUpbound, "log_base upbound");
        validateBase(base);
        validateValue(value);
        validateUpbound(upbound);
        return Math.log(Math.min(value, upbound)) / Math.log(base);
    }

    private static void validateBase(double base) {
        if (base <= 0.0 || base == 1.0) {
            throw new IllegalArgumentException(
                    "log_base base must be greater than zero and not equal to one");
        }
    }

    private static void validateValue(double value) {
        if (value <= 0.0) {
            throw new IllegalArgumentException("log_base value must be greater than zero");
        }
    }

    private static void validateUpbound(double upbound) {
        if (upbound <= 0.0) {
            throw new IllegalArgumentException("log_base upbound must be greater than zero");
        }
    }

    private static final class LogParameterKey {
        private final long baseBits;
        private final long upboundBits;

        private LogParameterKey(double base, double upbound) {
            this.baseBits = Double.doubleToLongBits(base);
            this.upboundBits = Double.doubleToLongBits(upbound);
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof LogParameterKey)) return false;
            LogParameterKey other = (LogParameterKey) value;
            return baseBits == other.baseBits && upboundBits == other.upboundBits;
        }

        @Override
        public int hashCode() {
            return 31 * Long.hashCode(baseBits) + Long.hashCode(upboundBits);
        }
    }

    private static final class LogParameters {
        private final double upbound;
        private final double logBase;

        private LogParameters(double upbound, double logBase) {
            this.upbound = upbound;
            this.logBase = logBase;
        }
    }
}
