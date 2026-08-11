package com.example.featuredag.definition;

/** 跨定义、逻辑、规划与运行时共享的值形状元数据（C1）。 */
public enum ValueShape {
    SCALAR,
    SEQUENCE,
    CANDIDATE_VECTOR,
    OBJECT,
    INDEX
}
