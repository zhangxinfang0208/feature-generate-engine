package com.example.featuredag.planning;

/** 节点值会随哪个执行维度变化，供阶段与缓存策略推导使用（C8/C10）。 */
public enum DependencyDimension {
    CONSTANT,
    USER,
    SCENE,
    ITEM
}
