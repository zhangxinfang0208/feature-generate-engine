package com.example.featuredag.operator;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.ValueShape;

import java.util.Set;

/**
 * 算子推断所需的 L0 只读输入元数据（C1）。
 * 逻辑节点实现该接口，但 operator 层不依赖任何逻辑节点类型。
 */
public interface OperatorInputMetadata {
    DataType outputType();
    Set<EntityScope> entityScopes();
    ValueShape valueShape();
    String sourceFeatureName();
}
