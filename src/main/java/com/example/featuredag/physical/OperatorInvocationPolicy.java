package com.example.featuredag.physical;

/** 通用算子节点在运行时值域上的固定 Single/Batch 分派策略（C10）。 */
public enum OperatorInvocationPolicy {
    SINGLE_OR_BATCH_BY_INPUT_DOMAIN
}
