package com.example.featuredag.runtime;

import com.example.featuredag.physical.PhysicalPlan;

import java.io.PrintStream;
import java.util.Objects;

/** 将显式开启的运行时值 trace 输出到控制台；默认单值最多打印 2000 个字符。 */
public final class ConsoleRuntimeTraceObserver implements RuntimeTraceObserver {
    private final PrintStream output;
    private final int maxValueLength;

    public ConsoleRuntimeTraceObserver() {
        this(System.out, RuntimeTracePrinter.DEFAULT_MAX_VALUE_LENGTH);
    }

    public ConsoleRuntimeTraceObserver(PrintStream output, int maxValueLength) {
        this.output = Objects.requireNonNull(output, "output");
        if (maxValueLength <= 0) {
            throw new IllegalArgumentException("maxValueLength must be positive");
        }
        this.maxValueLength = maxValueLength;
    }

    @Override
    public void onExecutionFinished(
            String executionId,
            PhysicalPlan plan,
            ExecutionResult result) {
        output.print(RuntimeTracePrinter.print(
                executionId, plan, result, maxValueLength));
    }
}
