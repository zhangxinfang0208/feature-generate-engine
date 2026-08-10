package com.example.featuredag.physical;

public final class PhysicalPlanPrinter {
    private PhysicalPlanPrinter() {}

    public static String print(PhysicalPlan plan) {
        StringBuilder builder = new StringBuilder();
        builder.append("Physical Plan ").append(plan.planId())
                .append(" [").append(plan.environment()).append("]\n");
        for (PhysicalNode node : plan.nodes()) {
            builder.append("- ").append(node.physicalNodeId())
                    .append(" executor=").append(node.executorType())
                    .append('/').append(node.executorId())
                    .append(" stage=").append(node.executionStage())
                    .append(" mode=").append(node.executionMode())
                    .append(" inputs=").append(node.inputSlots())
                    .append(" output=").append(node.outputSlot())
                    .append(" cache=").append(node.cachePolicy())
                    .append(" materialization=").append(node.materializationPolicy())
                    .append(" logical=").append(node.logicalNodeIds())
                    .append(" config=").append(node.executorConfig())
                    .append('\n');
        }
        builder.append("Outputs: ").append(plan.outputFeatureSlots()).append('\n');
        return builder.toString();
    }
}
