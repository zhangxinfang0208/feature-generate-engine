package com.example.featuredag.operator;

/** 运行时批维度；它位于逻辑 ValueShape 之外，不改变单行值的类型与形状。 */
public enum BatchDomain {
    OFFLINE_ROW,
    ONLINE_REQUEST,
    ONLINE_CANDIDATE
}
