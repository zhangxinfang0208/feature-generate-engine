package com.example.featuredag.logical;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.ValueShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 特征输出节点：每个特征在逻辑 DAG 中的唯一边界节点（C3），
 * 单输入（producer），输出角色决定它对外是变换输出/模型输入还是内部节点。
 */
public final class FeatureOutputNode extends AbstractLogicalNode {
    private final String featureName;
    private final String producerNodeId;
    private final OutputRole outputRole;
    private final Object defaultValue;

    public FeatureOutputNode(
            String nodeId,
            String featureName,
            String producerNodeId,
            DataType outputType,
            Set<EntityScope> entityScopes,
            ValueShape valueShape,
            OutputRole outputRole,
            String sourceExpression) {
        this(
                nodeId,
                featureName,
                producerNodeId,
                outputType,
                entityScopes,
                valueShape,
                outputRole,
                null,
                sourceExpression);
    }

    public FeatureOutputNode(
            String nodeId,
            String featureName,
            String producerNodeId,
            DataType outputType,
            Set<EntityScope> entityScopes,
            ValueShape valueShape,
            OutputRole outputRole,
            Object defaultValue,
            String sourceExpression) {
        super(nodeId, NodeType.FEATURE_OUTPUT,
                List.of(NodeInput.positional(producerNodeId, 0)),
                outputType, entityScopes, valueShape, featureName, sourceExpression);
        this.featureName = featureName;
        this.producerNodeId = producerNodeId;
        this.outputRole = outputRole;
        this.defaultValue = immutableValue(defaultValue);
    }

    public String featureName() { return featureName; }
    public String producerNodeId() { return producerNodeId; }
    public OutputRole outputRole() { return outputRole; }
    public Object defaultValue() { return defaultValue; }

    /** 默认值随逻辑节点冻结（C7），避免规划或运行阶段观察到外部容器变更。 */
    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(entry.getKey(), immutableValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object element : list) {
                copy.add(immutableValue(element));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
