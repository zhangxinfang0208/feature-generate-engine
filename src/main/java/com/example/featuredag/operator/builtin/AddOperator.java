package com.example.featuredag.operator.builtin;

import com.example.featuredag.operator.BorrowedArgumentsKernel;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.operator.OperatorInference;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * add：数值标量或等长序列加法，标量向序列广播。
 *
 * <p>普通整型载体通过带溢出检查的 long 求和，其余走精确十进制；双方均为整型载体时产出 Long（溢出直接失败
 * 不回绕，与 to_bigint 的失败语义一致），任一浮点载体产出 Double。
 * 类型推断按 DOUBLE &gt; BIGINT &gt; INT 取宽度上界。
 *
 * <p>不提供原生 BatchOperatorKernel：每行只做一次轻量数值加法，批内没有可复用的
 * 中间量，key 分配与查找开销反噬（成本模型见 AGENTS.md），
 * 由 SingleLoopBatchOperatorKernel 逐行适配，结果与 Single 完全一致。
 */
public final class AddOperator extends AbstractBuiltinOperator implements BorrowedArgumentsKernel {
    private static final List<String> PARAMETER_NAMES = Collections.unmodifiableList(
            Arrays.asList("value", "addend"));

    public AddOperator() {
        super("add", 2, 2, true, true);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.elementWiseNumericInference(
                name(), inputs, OperatorSupport.numericResultType(inputs));
    }

    @Override
    public List<String> parameterNames() {
        return PARAMETER_NAMES;
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        return OperatorSupport.evaluateElementWise(arguments, name(), this::evaluateScalars);
    }

    private Object evaluateScalars(List<Object> arguments) {
        Object left = arguments.get(0);
        Object right = arguments.get(1);
        // C6：仅对可无损表示为 long 的载体走快路径，保持 Long 输出与溢出失败语义。
        if (OperatorSupport.isLongCarrier(left) && OperatorSupport.isLongCarrier(right)) {
            try {
                return Long.valueOf(Math.addExact(
                        ((Number) left).longValue(), ((Number) right).longValue()));
            } catch (ArithmeticException overflow) {
                // 失败时回到精确路径，保留原有异常类型及完整结果文本；BigInteger 也始终走该路径。
            }
        }
        BigDecimal sum = OperatorSupport.arithmeticOperand(left, name())
                .add(OperatorSupport.arithmeticOperand(right, name()));
        return OperatorSupport.arithmeticCarrier(sum, left, right, name());
    }
}
