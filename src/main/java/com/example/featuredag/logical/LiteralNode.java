package com.example.featuredag.logical;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.ValueShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 字面量节点：表达式中的常量在逻辑 DAG 中的落点（C5），无输入、无实体域。
 */
public final class LiteralNode extends AbstractLogicalNode {
    private final Object value;

    public LiteralNode(String nodeId, Object value, DataType outputType, ValueShape valueShape,
                       String sourceFeatureName, String sourceExpression) {
        super(nodeId, NodeType.LITERAL, java.util.List.of(), outputType, Set.of(), valueShape,
                sourceFeatureName, sourceExpression);
        this.value = immutableValue(value);
    }

    public Object value() { return value; }

    /** 字面量按值深拷贝后存入节点（C7），Map/List 递归拷贝并包装，标量按引用。 */
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
