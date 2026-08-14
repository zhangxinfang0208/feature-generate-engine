package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;

import java.util.List;

/**
 * log_base：对数变换。
 *
 * <p>不提供原生 BatchOperatorKernel：批内只复用 log(base) 的预计算，
 * 每行仍需对 value 做 Math.log，复用收益不足以覆盖批开销（实测 batch 劣化约 0.1x），
 * 由 SingleLoopBatchOperatorKernel 逐行适配，结果与 Single 完全一致。
 */
public final class LogBaseOperator extends AbstractBuiltinOperator {
    public LogBaseOperator() {
        super("log_base", 3, 3, true, false);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.fixedInference(inputs, DataType.DOUBLE, ValueShape.SCALAR);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        return evaluateValues(arguments.get(0), arguments.get(1), arguments.get(2));
    }

    private static double evaluateValues(Object rawValue, Object rawBase, Object rawUpbound) {
        double value = OperatorSupport.finiteDouble(rawValue, "log_base value");
        double base = OperatorSupport.finiteDouble(rawBase, "log_base base");
        double upbound = OperatorSupport.finiteDouble(rawUpbound, "log_base upbound");
        validateBase(base);
        validateValue(value);
        validateUpbound(upbound);
        // 先按 upbound 截断再使用换底公式；Single Kernel 是标量适配 Batch 路径的语义基准。
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
}
