package com.example.featuredag.runtime;

import com.example.featuredag.physical.PhysicalNode;
import com.example.featuredag.physical.PhysicalPlan;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 将一次执行的物理节点输入、输出和状态格式化为便于人工检查的文本。 */
public final class RuntimeTracePrinter {
    public static final int DEFAULT_MAX_VALUE_LENGTH = 2_000;

    private RuntimeTracePrinter() {}

    public static String print(
            String executionId,
            PhysicalPlan plan,
            ExecutionResult result) {
        return print(executionId, plan, result, DEFAULT_MAX_VALUE_LENGTH);
    }

    public static String print(
            String executionId,
            PhysicalPlan plan,
            ExecutionResult result,
            int maxValueLength) {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(result, "result");
        if (maxValueLength <= 0) {
            throw new IllegalArgumentException("maxValueLength must be positive");
        }

        Map<String, PhysicalNode> producersBySlot = new LinkedHashMap<>();
        for (PhysicalNode node : plan.nodes()) {
            producersBySlot.put(node.outputSlot(), node);
        }

        StringBuilder builder = new StringBuilder();
        builder.append("Runtime Trace ")
                .append(executionId)
                .append(" [plan=")
                .append(plan.planId())
                .append("]\n");
        for (PhysicalNode node : plan.nodes()) {
            RuntimeNodeState state = result.nodeStates().get(node.physicalNodeId());
            builder.append("- ")
                    .append(node.physicalNodeId())
                    .append(" executor=")
                    .append(node.executorId())
                    .append(" logical=")
                    .append(node.logicalNodeIds())
                    .append(" status=")
                    .append(state == null ? ExecutionStatus.NOT_STARTED : state.status())
                    .append('\n');
            Object operatorName = node.executorConfig().get("operatorName");
            if (operatorName != null) {
                builder.append("  operator=").append(operatorName).append('\n');
            }
            for (String inputSlot : node.inputSlots()) {
                PhysicalNode producer = producersBySlot.get(inputSlot);
                RuntimeNodeState producerState = producer == null
                        ? null
                        : result.nodeStates().get(producer.physicalNodeId());
                builder.append("  input ")
                        .append(inputSlot)
                        .append(" <- ")
                        .append(producer == null ? "<external>" : producer.physicalNodeId())
                        .append(" = ")
                        .append(formatValue(
                                producerState == null ? null : producerState.resultHandle(),
                                maxValueLength))
                        .append('\n');
            }
            builder.append("  output ")
                    .append(node.outputSlot())
                    .append(" = ")
                    .append(formatValue(
                            state == null ? null : state.resultHandle(),
                            maxValueLength))
                    .append('\n');
            if (state != null) {
                builder.append("  durationNanos=")
                        .append(state.durationNanos())
                        .append(" cacheHit=")
                        .append(state.cacheHit())
                        .append('\n');
                if (state.error() != null) {
                    builder.append("  error=")
                            .append(state.error().getClass().getName())
                            .append(": ")
                            .append(state.error().getMessage())
                            .append('\n');
                }
            }
        }
        return builder.toString();
    }

    private static String formatValue(ValueHandle handle, int maxValueLength) {
        if (handle == null) return "<not produced>";
        Object value;
        try {
            value = new ExternalValueMaterializer().materialize(handle);
        } catch (RuntimeException unsupported) {
            Object raw = handle.raw();
            value = raw == handle
                    ? "<" + handle.getClass().getSimpleName() + ">"
                    : raw;
        }
        String text = String.valueOf(value);
        if (text.length() <= maxValueLength) return text;
        return text.substring(0, maxValueLength)
                + "...<truncated, totalChars=" + text.length() + ">";
    }
}
