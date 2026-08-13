package com.example.featuredag.logical;

/**
 * 逻辑节点类型（C5）：与节点 ID 前缀规范一一对应——
 * source:/literal:/operator:/feature: 分别对应 SOURCE/LITERAL/OPERATOR/FEATURE_OUTPUT。
 */
public enum NodeType {
    SOURCE,
    LITERAL,
    OPERATOR,
    FEATURE_OUTPUT
}
