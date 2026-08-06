package com.example.featuredag.logical;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.FeatureDefinition;
import com.example.featuredag.definition.FeatureRole;
import com.example.featuredag.definition.OutputPolicy;
import com.example.featuredag.expression.AstCall;
import com.example.featuredag.expression.AstArrayLiteral;
import com.example.featuredag.expression.AstFeatureRef;
import com.example.featuredag.expression.AstLiteral;
import com.example.featuredag.expression.AstNode;
import com.example.featuredag.expression.AstObjectLiteral;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.operator.OperatorDefinition;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorRegistry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Builds a target-driven logical DAG. AST objects are temporary and never
 * become part of the persisted plan model.
 */
public final class LogicalDagBuilder {
    private final ExpressionParser expressionParser;
    private final OperatorRegistry operatorRegistry;

    public LogicalDagBuilder(ExpressionParser expressionParser, OperatorRegistry operatorRegistry) {
        this.expressionParser = expressionParser;
        this.operatorRegistry = operatorRegistry;
    }

    public LogicalDag build(List<FeatureDefinition> definitions, Set<String> targetFeatures) {
        if (definitions == null || definitions.isEmpty()) {
            throw new DagBuildException("Feature definitions must not be empty");
        }
        if (targetFeatures == null || targetFeatures.isEmpty()) {
            throw new DagBuildException("Target feature set must not be empty");
        }
        BuildContext context = new BuildContext(definitions);
        Set<String> roots = new LinkedHashSet<>();
        for (String target : targetFeatures) {
            roots.add(buildFeature(target, context));
        }
        List<String> topologicalOrder = topologicalSort(context.nodes);
        return new LogicalDag(context.nodes, roots, context.featureOutputIds, topologicalOrder);
    }

    private String buildFeature(String featureName, BuildContext context) {
        String existing = context.featureOutputIds.get(featureName);
        if (existing != null) return existing;

        VisitState state = context.states.get(featureName);
        if (state == VisitState.VISITING) {
            List<String> cycle = new ArrayList<>(context.featureStack);
            Collections.reverse(cycle);
            cycle.add(featureName);
            throw new DagBuildException("Feature dependency cycle detected: " + String.join(" -> ", cycle));
        }
        if (state == VisitState.VISITED) {
            return context.featureOutputIds.get(featureName);
        }

        FeatureDefinition definition = context.definitions.get(featureName);
        if (definition == null) {
            throw new DagBuildException("Referenced feature is not defined: " + featureName);
        }

        context.states.put(featureName, VisitState.VISITING);
        context.featureStack.push(featureName);
        try {
            String producerId;
            if (definition.isRaw()) {
                producerId = createSourceNode(definition, context);
            } else {
                AstNode ast;
                try {
                    ast = expressionParser.parse(definition.expressionContent());
                } catch (RuntimeException ex) {
                    throw new DagBuildException(
                            "Failed to parse expression for feature " + featureName + ": " + ex.getMessage(), ex);
                }
                producerId = buildAst(ast, definition, context);
            }

            LogicalNode producer = context.nodes.get(producerId);
            validateDeclaredType(definition, producer.outputType());
            validateDeclaredShapeAndScopes(definition, producer);
            OutputRole role = switch (definition.role()) {
                case MODEL_INPUT -> OutputRole.MODEL_INPUT;
                case INTERMEDIATE -> OutputRole.INTERNAL;
                default -> definition.outputPolicy() == OutputPolicy.OUTPUT
                        ? OutputRole.TRANSFORM_OUTPUT : OutputRole.INTERNAL;
            };
            String outputId = "feature:" + featureName;
            FeatureOutputNode outputNode = new FeatureOutputNode(
                    outputId,
                    featureName,
                    producerId,
                    producer.outputType(),
                    producer.entityScopes(),
                    producer.valueShape(),
                    role,
                    definition.expressionContent());
            context.nodes.put(outputId, outputNode);
            context.featureOutputIds.put(featureName, outputId);
            context.states.put(featureName, VisitState.VISITED);
            return outputId;
        } finally {
            context.featureStack.pop();
        }
    }

    private String createSourceNode(FeatureDefinition definition, BuildContext context) {
        String signature = "source|" + definition.name();
        String existing = context.canonicalNodeIds.get(signature);
        if (existing != null) return existing;
        String nodeId = "source:" + definition.name();
        ValueShape shape = definition.declaredValueShape() == null
                ? shapeForType(definition.dataType())
                : definition.declaredValueShape();
        SourceNode node = new SourceNode(
                nodeId,
                definition.name(),
                definition.dataType(),
                definition.entityScopes(),
                shape,
                definition.defaultValue(),
                definition.sourceBinding() == null ? definition.name() : definition.sourceBinding());
        context.nodes.put(nodeId, node);
        context.canonicalNodeIds.put(signature, nodeId);
        return nodeId;
    }

    private String buildAst(AstNode ast, FeatureDefinition owner, BuildContext context) {
        if (ast instanceof AstFeatureRef featureRef) {
            return buildFeature(featureRef.featureName(), context);
        }
        if (ast instanceof AstLiteral literal) {
            return createLiteralNode(literal.value(), owner, context);
        }
        if (ast instanceof AstArrayLiteral arrayLiteral) {
            List<Object> values = new ArrayList<>();
            for (AstNode element : arrayLiteral.elements()) {
                values.add(toLiteralValue(element));
            }
            return createLiteralNode(List.copyOf(values), owner, context);
        }
        if (ast instanceof AstObjectLiteral objectLiteral) {
            Map<String, Object> value = new LinkedHashMap<>();
            for (Map.Entry<String, AstNode> entry : objectLiteral.fields().entrySet()) {
                value.put(entry.getKey(), toLiteralValue(entry.getValue()));
            }
            return createLiteralNode(value, owner, context);
        }
        if (ast instanceof AstCall call) {
            List<String> inputIds = new ArrayList<>();
            for (AstNode argument : call.arguments()) {
                inputIds.add(buildAst(argument, owner, context));
            }
            return createOperatorNode(call.functionName(), inputIds, owner, context);
        }
        throw new DagBuildException("Unsupported AST node: " + ast.getClass().getName());
    }

    private Object toLiteralValue(AstNode node) {
        if (node instanceof AstLiteral literal) return literal.value();
        if (node instanceof AstArrayLiteral arrayLiteral) {
            List<Object> values = new ArrayList<>();
            for (AstNode element : arrayLiteral.elements()) {
                values.add(toLiteralValue(element));
            }
            return List.copyOf(values);
        }
        if (node instanceof AstObjectLiteral objectLiteral) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, AstNode> entry : objectLiteral.fields().entrySet()) {
                map.put(entry.getKey(), toLiteralValue(entry.getValue()));
            }
            return map;
        }
        throw new DagBuildException("Object literal values must be literals; found: " + node.getClass().getSimpleName());
    }

    private String createLiteralNode(Object value, FeatureDefinition owner, BuildContext context) {
        DataType dataType = inferLiteralType(value);
        ValueShape shape = dataType == DataType.OBJECT ? ValueShape.OBJECT : ValueShape.SCALAR;
        String signature = "literal|" + dataType + "|" + canonicalValue(value);
        String existing = context.canonicalNodeIds.get(signature);
        if (existing != null) return existing;
        String nodeId = "literal:" + context.sequence.incrementAndGet();
        LiteralNode node = new LiteralNode(
                nodeId,
                value,
                dataType,
                shape,
                owner.name(),
                owner.expressionContent());
        context.nodes.put(nodeId, node);
        context.canonicalNodeIds.put(signature, nodeId);
        return nodeId;
    }

    private String createOperatorNode(
            String operatorName,
            List<String> inputIds,
            FeatureDefinition owner,
            BuildContext context) {
        List<LogicalNode> inputs = inputIds.stream().map(context.nodes::get).toList();
        OperatorDefinition definition;
        OperatorInference inference;
        try {
            definition = operatorRegistry.require(operatorName);
            inference = operatorRegistry.infer(operatorName, inputs);
        } catch (RuntimeException ex) {
            throw new DagBuildException(
                    "Invalid operator " + operatorName + " in feature " + owner.name() + ": " + ex.getMessage(), ex);
        }

        String signature = "operator|" + operatorName + "|" + String.join(",", inputIds);
        String existing = context.canonicalNodeIds.get(signature);
        if (existing != null) return existing;

        List<NodeInput> nodeInputs = new ArrayList<>();
        for (int i = 0; i < inputIds.size(); i++) {
            nodeInputs.add(NodeInput.positional(inputIds.get(i), i));
        }
        String nodeId = "operator:" + operatorName + ":" + context.sequence.incrementAndGet();
        OperatorNode node = new OperatorNode(
                nodeId,
                operatorName,
                nodeInputs,
                inference.outputType(),
                inference.entityScopes(),
                inference.valueShape(),
                Map.of(),
                definition.deterministic(),
                definition.parameterized(),
                owner.name(),
                owner.expressionContent());
        context.nodes.put(nodeId, node);
        context.canonicalNodeIds.put(signature, nodeId);
        return nodeId;
    }

    private static void validateDeclaredType(FeatureDefinition definition, DataType inferredType) {
        if (definition.dataType() != DataType.UNKNOWN
                && inferredType != DataType.UNKNOWN
                && definition.dataType() != inferredType
                && !(definition.dataType() == DataType.DOUBLE && inferredType == DataType.INT)) {
            throw new DagBuildException(
                    "Declared type mismatch for feature " + definition.name()
                            + ": declared=" + definition.dataType() + ", inferred=" + inferredType);
        }
    }

    private static void validateDeclaredShapeAndScopes(
            FeatureDefinition definition, LogicalNode producer) {
        if (definition.isRaw()) return;
        if (definition.declaredValueShape() != null
                && definition.declaredValueShape() != producer.valueShape()) {
            throw new DagBuildException(
                    "Declared value shape mismatch for feature " + definition.name()
                            + ": declared=" + definition.declaredValueShape()
                            + ", inferred=" + producer.valueShape());
        }
        if (!definition.entityScopes().isEmpty()
                && !definition.entityScopes().equals(producer.entityScopes())) {
            throw new DagBuildException(
                    "Declared entity scopes mismatch for feature " + definition.name()
                            + ": declared=" + definition.entityScopes()
                            + ", inferred=" + producer.entityScopes());
        }
    }

    private static List<String> topologicalSort(Map<String, LogicalNode> nodes) {
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, List<String>> consumers = new LinkedHashMap<>();
        for (String nodeId : nodes.keySet()) {
            inDegree.put(nodeId, 0);
            consumers.put(nodeId, new ArrayList<>());
        }
        for (LogicalNode node : nodes.values()) {
            inDegree.put(node.nodeId(), node.inputs().size());
            for (NodeInput input : node.inputs()) {
                consumers.get(input.nodeId()).add(node.nodeId());
            }
        }
        Deque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) ready.add(entry.getKey());
        }
        List<String> order = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            String current = ready.removeFirst();
            order.add(current);
            for (String consumer : consumers.get(current)) {
                int remaining = inDegree.compute(consumer, (k, v) -> v - 1);
                if (remaining == 0) ready.addLast(consumer);
            }
        }
        if (order.size() != nodes.size()) {
            Set<String> unresolved = new LinkedHashSet<>(nodes.keySet());
            unresolved.removeAll(order);
            throw new DagBuildException("Logical DAG contains a cycle; unresolved nodes: " + unresolved);
        }
        return List.copyOf(order);
    }

    private static DataType inferLiteralType(Object value) {
        if (value == null) return DataType.UNKNOWN;
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return DataType.INT;
        }
        if (value instanceof Number) return DataType.DOUBLE;
        if (value instanceof String) return DataType.STRING;
        if (value instanceof Boolean) return DataType.BOOLEAN;
        if (value instanceof Map<?, ?>) return DataType.OBJECT;
        return DataType.OBJECT;
    }

    private static ValueShape shapeForType(DataType type) {
        return switch (type) {
            case EVENT_SEQUENCE -> ValueShape.SEQUENCE;
            case OBJECT -> ValueShape.OBJECT;
            default -> ValueShape.SCALAR;
        };
    }

    private static String canonicalValue(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(LogicalDagBuilder::canonicalValue)
                    .collect(Collectors.joining(",", "[", "]"));
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey((a, b) -> String.valueOf(a).compareTo(String.valueOf(b))))
                    .map(entry -> String.valueOf(entry.getKey()) + "=" + canonicalValue(entry.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        return String.valueOf(value);
    }

    private enum VisitState { VISITING, VISITED }

    private static final class BuildContext {
        private final Map<String, FeatureDefinition> definitions;
        private final Map<String, LogicalNode> nodes = new LinkedHashMap<>();
        private final Map<String, String> featureOutputIds = new LinkedHashMap<>();
        private final Map<String, String> canonicalNodeIds = new HashMap<>();
        private final Map<String, VisitState> states = new HashMap<>();
        private final Deque<String> featureStack = new ArrayDeque<>();
        private final AtomicInteger sequence = new AtomicInteger();

        private BuildContext(List<FeatureDefinition> definitions) {
            this.definitions = new LinkedHashMap<>();
            for (FeatureDefinition definition : definitions) {
                FeatureDefinition previous = this.definitions.putIfAbsent(definition.name(), definition);
                if (previous != null) {
                    throw new DagBuildException("Duplicate feature definition: " + definition.name());
                }
            }
        }
    }
}
