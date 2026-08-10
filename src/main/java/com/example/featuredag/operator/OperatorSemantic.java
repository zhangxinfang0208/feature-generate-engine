package com.example.featuredag.operator;

/**
 * 算子的逻辑语义标记（L0）：只描述算子“是什么”，不得引用规划、物理或运行时类型（C1）。
 * 规划规则通过这些语义匹配 DAG 结构，避免按算子名称写死优化逻辑。
 */
public interface OperatorSemantic {
}
