package com.example.featuredag.physical;

/**
 * Kernel 输入中的序列视图处理方式（C10）：规划期固化，运行时只执行对应适配。
 */
public enum SequenceViewInputMode {
    DIRECT,
    MATERIALIZE
}
