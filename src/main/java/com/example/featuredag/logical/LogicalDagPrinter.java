package com.example.featuredag.logical;

import java.util.stream.Collectors;

public final class LogicalDagPrinter {
    private LogicalDagPrinter() {}

    public static String print(LogicalDag dag) {
        StringBuilder builder = new StringBuilder();
        builder.append("Logical DAG\n");
        builder.append("Roots: ").append(dag.rootNodeIds()).append('\n');
        for (String nodeId : dag.topologicalOrder()) {
            LogicalNode node = dag.node(nodeId);
            String inputs = node.inputs().stream()
                    .map(input -> input.inputPort() + ":" + input.nodeId())
                    .collect(Collectors.joining(", "));
            builder.append("- ")
                    .append(node.nodeId())
                    .append(" [").append(node.nodeType()).append("]")
                    .append(" inputs={").append(inputs).append("}")
                    .append(" type=").append(node.outputType())
                    .append(" scopes=").append(node.entityScopes())
                    .append(" shape=").append(node.valueShape());
            if (node instanceof OperatorNode operator) {
                builder.append(" operator=").append(operator.operatorName());
            } else if (node instanceof FeatureOutputNode output) {
                builder.append(" feature=").append(output.featureName());
            } else if (node instanceof SourceNode source) {
                builder.append(" source=").append(source.featureName());
            }
            builder.append('\n');
        }
        return builder.toString();
    }
}
