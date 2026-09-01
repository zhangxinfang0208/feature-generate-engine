package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;

import java.util.List;

/**
 * log_base：对数变换；value 与 base 支持等长序列和标量广播，upbound 固定为标量。
 *
 * <p>全标量调用保持原有行为；任一 value/base 输入为序列时按逻辑下标逐元素计算。
 * 不提供原生 BatchOperatorKernel：批内只复用 log(base) 的预计算，
 * 每行仍需对 value 做 Math.log，复用收益不足以覆盖批开销（实测 batch 劣化约 0.1x），
 * 由 SingleLoopBatchOperatorKernel 逐行适配，结果与 Single 完全一致。
 */
public final class LogBaseOperator extends AbstractBuiltinOperator {
    public LogBaseOperator() {
        super("log_base", 3, 3, true, true);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        OperatorInference elementWise = OperatorSupport.elementWiseNumericInference(
                name(), inputs.subList(0, 2), DataType.DOUBLE);
        OperatorInputMetadata upbound = inputs.get(2);
        if (!upbound.outputType().isNumeric()) {
            throw new IllegalArgumentException(
                    "log_base upbound requires numeric scalar input, got: "
                            + upbound.outputType());
        }
        if (upbound.valueShape() != ValueShape.SCALAR
                && upbound.valueShape() != ValueShape.CANDIDATE_VECTOR) {
            throw new IllegalArgumentException(
                    "log_base upbound must be scalar, got: " + upbound.valueShape());
        }
        return OperatorSupport.fixedInference(
                inputs, DataType.DOUBLE, elementWise.valueShape());
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        double upbound = OperatorSupport.finiteDouble(
                arguments.get(2), "log_base upbound");
        validateUpbound(upbound);
        return OperatorSupport.evaluateElementWise(
                arguments.subList(0, 2),
                name(),
                values -> evaluateValues(values.get(0), values.get(1), upbound));
    }

    private static double evaluateValues(Object rawValue, Object rawBase, double upbound) {
        double value = OperatorSupport.finiteDouble(rawValue, "log_base value");
        double base = OperatorSupport.finiteDouble(rawBase, "log_base base");
        validateBase(base);
        validateValue(value);
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
