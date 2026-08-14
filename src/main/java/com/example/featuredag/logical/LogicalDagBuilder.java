package com.example.featuredag.logical;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.FeatureDefinition;
import com.example.featuredag.definition.FeatureRole;
import com.example.featuredag.definition.OutputPolicy;
import com.example.featuredag.definition.ValueShape;
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

/**
 * Builds a target-driven logical DAG. AST objects are temporary and never
 * become part of the persisted plan model.
 *
 * 逻辑层（L1）构建器：从目标特征出发逆向构建可达子图（C3），
 * 表达式 AST 仅是临时中间表示，构建完成后即丢弃；
 * 通过 canonical 签名合并等价节点（C5），并完成环检测（C4）
 * 与声明/推断一致性校验（C6）。
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
        // C3：目标驱动构建——只展开目标特征及其依赖的可达子图，其余特征不进入 DAG
        BuildContext context = new BuildContext(definitions);
        Set<String> roots = new LinkedHashSet<>();
        for (String target : targetFeatures) {
            roots.add(buildFeature(target, context));
        }
        // C4：拓扑排序既是执行顺序，也是对环的兜底校验
        List<String> topologicalOrder = topologicalSort(context.nodes);
        // C3/C5：构建产物是「节点表 + 根输出 + 特征输出映射 + 拓扑序」的不可变快照（C7），
        // 作为规划层（C8）与物理层（C9）的输入
        return new LogicalDag(context.nodes, roots, context.featureOutputIds, topologicalOrder);
    }

    /**
     * 构建单个特征的输出节点（DFS 展开）：
     * ① 查输出映射/访问状态，已构建则复用，VISITING 说明出现依赖环（C4）；
     * ② RAW 特征直接落为源节点，DERIVED 特征先解析表达式再递归构建子树；
     * ③ 对生成节点做声明/推断一致性校验（C6），最后包一层 FEATURE_OUTPUT 边界节点（C3/C5）。
     */
    private String buildFeature(String featureName, BuildContext context) {
        String existing = context.featureOutputIds.get(featureName);
        if (existing != null) return existing;

        // C4：DFS 三色标记检测特征依赖环——VISITING 表示该特征还在当前展开栈内
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
            // 边界类型优先取声明类型：C6 唯一放宽的声明 DOUBLE / 推断 INT 场景下，
            // 特征输出节点仍需对外承诺 DOUBLE（供运行时定宽、下游算子按声明类型推断）。
            DataType boundaryType = definition.dataType() != DataType.UNKNOWN
                    ? definition.dataType()
                    : producer.outputType();
            // C3/C5：每个特征对应唯一 FEATURE_OUTPUT 边界节点（ID 前缀 feature:）
            String outputId = "feature:" + featureName;
            FeatureOutputNode outputNode = new FeatureOutputNode(
                    outputId,
                    featureName,
                    producerId,
                    boundaryType,
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

    /**
     * RAW 特征落点为源节点（C2/C5）：无输入，值形状按声明或类型推断，
     * 携带默认值与源绑定名；按 canonical 签名去重，全图共享同一个源节点。
     */
    private String createSourceNode(FeatureDefinition definition, BuildContext context) {
        // C5：同一 RAW 特征只建一个源节点，按 canonical 签名去重
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

    /**
     * AST → 逻辑节点 的递归转换（C3）：
     * 特征引用展开为对应特征的输出节点；字面量（含数组/对象折叠为字面量值）落为 LiteralNode；
     * 函数调用先递归构建参数子树，再落为 OperatorNode。
     */
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
            return createLiteralNode(immutableLiteralList(values), owner, context);
        }
        if (ast instanceof AstObjectLiteral objectLiteral) {
            Map<String, Object> value = new LinkedHashMap<>();
            for (Map.Entry<String, AstNode> entry : objectLiteral.fields().entrySet()) {
                value.put(entry.getKey(), toLiteralValue(entry.getValue()));
            }
            return createLiteralNode(immutableLiteralMap(value), owner, context);
        }
        if (ast instanceof AstCall call) {
            validateInvocationStyle(call, owner);
            List<AstNode> arguments = bindNamedArguments(call, owner);
            List<String> inputIds = new ArrayList<>();
            for (AstNode argument : arguments) {
                inputIds.add(buildAst(argument, owner, context));
            }
            return createOperatorNode(call.functionName(), inputIds, owner, context);
        }
        throw new DagBuildException("Unsupported AST node: " + ast.getClass().getName());
    }

    private void validateInvocationStyle(AstCall call, FeatureDefinition owner) {
        // 普通调用只需一次括号；链式/柯里化语法必须由算子元数据显式声明支持。
        if (call.invocationCount() == 1) return;
        OperatorDefinition definition;
        try {
            definition = operatorRegistry.require(call.functionName());
        } catch (RuntimeException error) {
            throw new DagBuildException(
                    "Invalid operator " + call.functionName() + " in feature "
                            + owner.name() + ": " + error.getMessage(),
                    error);
        }
        if (!definition.supportsCurriedInvocation()) {
            throw new DagBuildException(
                    "Operator " + call.functionName()
                            + " does not support chained invocation in feature " + owner.name());
        }
    }

    /**
     * C3/C5：命名参数仅存在于临时 AST；构图前按算子元数据归一化为位置参数，
     * 使等价的命名/位置写法共享同一 canonical 逻辑节点，且不污染规划与运行时协议。
     */
    private List<AstNode> bindNamedArguments(AstCall call, FeatureDefinition owner) {
        if (!call.hasNamedArguments()) return call.arguments();

        OperatorDefinition definition;
        try {
            definition = operatorRegistry.require(call.functionName());
        } catch (RuntimeException error) {
            throw new DagBuildException(
                    "Invalid operator " + call.functionName() + " in feature "
                            + owner.name() + ": " + error.getMessage(),
                    error);
        }

        List<String> parameterNames = definition.parameterNames();
        if (parameterNames.isEmpty()) {
            throw new DagBuildException(
                    "Operator " + call.functionName()
                            + " does not support named arguments in feature " + owner.name());
        }
        if (call.arguments().size() > parameterNames.size()) {
            throw new DagBuildException(
                    "Operator " + call.functionName() + " expects at most "
                            + parameterNames.size() + " arguments, got " + call.arguments().size()
                            + " in feature " + owner.name());
        }

        Map<String, Integer> indexesByName = new LinkedHashMap<>();
        for (int index = 0; index < parameterNames.size(); index++) {
            indexesByName.put(parameterNames.get(index), index);
        }
        AstNode[] bound = new AstNode[parameterNames.size()];
        int nextPositionalIndex = 0;
        boolean namedArgumentSeen = false;
        for (int sourceIndex = 0; sourceIndex < call.arguments().size(); sourceIndex++) {
            String parameterName = call.argumentName(sourceIndex);
            int targetIndex;
            if (parameterName == null) {
                if (namedArgumentSeen) {
                    throw new DagBuildException(
                            "Positional argument must not follow a named argument for operator "
                                    + call.functionName() + " in feature " + owner.name());
                }
                targetIndex = nextPositionalIndex++;
            } else {
                namedArgumentSeen = true;
                Integer declaredIndex = indexesByName.get(parameterName);
                if (declaredIndex == null) {
                    throw new DagBuildException(
                            "Unknown named argument '" + parameterName + "' for operator "
                                    + call.functionName() + "; expected one of " + parameterNames
                                    + " in feature " + owner.name());
                }
                targetIndex = declaredIndex;
            }
            if (bound[targetIndex] != null) {
                throw new DagBuildException(
                        "Argument '" + parameterNames.get(targetIndex)
                                + "' is specified more than once for operator "
                                + call.functionName() + " in feature " + owner.name());
            }
            bound[targetIndex] = call.arguments().get(sourceIndex);
        }

        List<AstNode> ordered = new ArrayList<>();
        for (int index = 0; index < bound.length; index++) {
            if (bound[index] == null) {
                if (index < definition.minArguments()) {
                    throw new DagBuildException(
                            "Missing required argument '" + parameterNames.get(index)
                                    + "' for operator " + call.functionName()
                                    + " in feature " + owner.name());
                }
                for (int laterIndex = index + 1; laterIndex < bound.length; laterIndex++) {
                    if (bound[laterIndex] != null) {
                        throw new DagBuildException(
                                "Named arguments for operator " + call.functionName()
                                        + " cannot skip parameter '" + parameterNames.get(index)
                                        + "' in feature " + owner.name());
                    }
                }
                break;
            }
            ordered.add(bound[index]);
        }
        return List.copyOf(ordered);
    }

    private Object toLiteralValue(AstNode node) {
        // 复合字面量在构图期折叠为普通不可变值，不为数组/对象内部元素创建额外逻辑节点。
        if (node instanceof AstLiteral literal) return literal.value();
        if (node instanceof AstArrayLiteral arrayLiteral) {
            List<Object> values = new ArrayList<>();
            for (AstNode element : arrayLiteral.elements()) {
                values.add(toLiteralValue(element));
            }
            return immutableLiteralList(values);
        }
        if (node instanceof AstObjectLiteral objectLiteral) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, AstNode> entry : objectLiteral.fields().entrySet()) {
                map.put(entry.getKey(), toLiteralValue(entry.getValue()));
            }
            return immutableLiteralMap(map);
        }
        throw new DagBuildException("Object literal values must be literals; found: " + node.getClass().getSimpleName());
    }

    private static List<Object> immutableLiteralList(List<Object> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    /** 对象字面量冻结为不可变 Map（C7）：嵌套 List/Map 已由 toLiteralValue 递归冻结。 */
    private static Map<String, Object> immutableLiteralMap(Map<String, Object> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /**
     * 字面量落点为 LiteralNode（C5）：按值类型推断 DataType 与值形状，
     * 相同的「类型 + 值」只保留一个节点，避免常量重复构建。
     */
    private String createLiteralNode(Object value, FeatureDefinition owner, BuildContext context) {
        DataType dataType = inferLiteralType(value);
        // 数组字面量折叠为 List，属序列形状（C6）：按值本身而非 DataType 判定形状，避免规划层按标量估算
        ValueShape shape = value instanceof List<?>
                ? ValueShape.SEQUENCE
                : dataType == DataType.OBJECT ? ValueShape.OBJECT : ValueShape.SCALAR;
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

    /**
     * 函数调用落点为 OperatorNode（C5/C6）：
     * 先经算子注册表校验存在性与参数个数，再由 OperatorInference 依据输入节点
     * 推断输出类型/实体域/值形状；相同「算子名 + 输入集合」只保留一个节点。
     */
    private String createOperatorNode(
            String operatorName,
            List<String> inputIds,
            FeatureDefinition owner,
            BuildContext context) {
        List<LogicalNode> inputs = inputIds.stream().map(context.nodes::get).toList();
        // C6：输出类型/实体域/值形状由算子注册表基于输入节点推断
        OperatorDefinition definition;
        OperatorInference inference;
        try {
            definition = operatorRegistry.require(operatorName);
            inference = operatorRegistry.infer(operatorName, inputs);
        } catch (RuntimeException ex) {
            throw new DagBuildException(
                    "Invalid operator " + operatorName + " in feature " + owner.name() + ": " + ex.getMessage(), ex);
        }

        // C5：相同算子名 + 相同输入集合的调用合并为同一个算子节点
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
                owner.name(),
                owner.expressionContent());
        context.nodes.put(nodeId, node);
        context.canonicalNodeIds.put(signature, nodeId);
        return nodeId;
    }

    /**
     * 约束 C6：声明类型与推断类型必须一致；唯一例外是声明 DOUBLE、推断为 INT 的放宽。
     */
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
        // C6：只有显式声明才参与比对；未声明的形状和实体域完全采用算子链推断结果。
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

    /**
     * Kahn 拓扑排序（C4）：按入度表 + 就绪队列逐层剥离零入度节点，
     * 产出的顺序既是执行顺序，也是下游消费输入的前置保证；
     * 若队列提前耗尽说明图中存在环。
     */
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
                List<String> inputConsumers = consumers.get(input.nodeId());
                if (inputConsumers == null) {
                    throw new DagBuildException(
                            "Logical node " + node.nodeId()
                                    + " references missing input node: " + input.nodeId());
                }
                inputConsumers.add(node.nodeId());
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
        // C4：拓扑排序兜底——仍有未排入序的节点说明逻辑 DAG 中存在环
        if (order.size() != nodes.size()) {
            Set<String> unresolved = new LinkedHashSet<>(nodes.keySet());
            unresolved.removeAll(order);
            throw new DagBuildException("Logical DAG contains a cycle; unresolved nodes: " + unresolved);
        }
        return List.copyOf(order);
    }

    private static DataType inferLiteralType(Object value) {
        // 字面量只做构图所需的最小类型归类；复杂集合统一视为 OBJECT，不推断元素泛型。
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
        // C5：长度前缀编码避免简单字符串拼接歧义；Map 排序后编码，使键迭代顺序不影响去重。
        if (value == null) {
            return frame("N", "");
        }
        if (value instanceof List<?> list) {
            StringBuilder payload = new StringBuilder();
            for (Object element : list) {
                payload.append(frame("E", canonicalValue(element)));
            }
            return frame("L", payload.toString());
        }
        if (value instanceof Map<?, ?> map) {
            List<CanonicalMapEntry> entries = new ArrayList<>(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                entries.add(new CanonicalMapEntry(
                        canonicalValue(entry.getKey()), canonicalValue(entry.getValue())));
            }
            entries.sort((left, right) -> {
                int keyComparison = left.key().compareTo(right.key());
                return keyComparison != 0
                        ? keyComparison
                        : left.value().compareTo(right.value());
            });
            StringBuilder payload = new StringBuilder();
            for (CanonicalMapEntry entry : entries) {
                payload.append(frame("K", entry.key()));
                payload.append(frame("V", entry.value()));
            }
            return frame("M", payload.toString());
        }
        String atom = frame("T", value.getClass().getName())
                + frame("V", String.valueOf(value));
        return frame("A", atom);
    }

    private static String frame(String tag, String payload) {
        return tag + payload.length() + ":" + payload;
    }

    private record CanonicalMapEntry(String key, String value) {}

    private enum VisitState { VISITING, VISITED }

    /**
     * 构建期上下文（C3/C4/C5）：持有定义表、节点表、特征输出映射、
     * canonical 去重表与 DFS 三色标记，仅存在于本次构建过程中。
     */
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
