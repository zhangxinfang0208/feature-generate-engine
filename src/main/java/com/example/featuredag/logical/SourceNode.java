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
 * 源节点：L0 定义的 RAW 特征在逻辑 DAG 中的落点（C2/C5）。
 * 无输入，携带特征名、默认值与源绑定名，实体域来自特征定义声明。
 */
public final class SourceNode extends AbstractLogicalNode {
    private final String featureName;
    private final Object defaultValue;
    private final String sourceBinding;

    public SourceNode(
            String nodeId,
            String featureName,
            DataType outputType,
            Set<EntityScope> entityScopes,
            ValueShape valueShape,
            Object defaultValue,
            String sourceBinding) {
        super(nodeId, NodeType.SOURCE, java.util.List.of(), outputType, entityScopes,
                valueShape, featureName, null);
        this.featureName = featureName;
        this.defaultValue = immutableValue(defaultValue);
        this.sourceBinding = sourceBinding;
    }

    public String featureName() { return featureName; }
    public Object defaultValue() { return defaultValue; }
    public String sourceBinding() { return sourceBinding; }

    /** 默认值按值深拷贝后存入节点（C7），Map/List 递归拷贝并包装，标量按引用。 */
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
