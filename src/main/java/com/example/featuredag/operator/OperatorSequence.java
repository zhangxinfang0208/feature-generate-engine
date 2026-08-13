package com.example.featuredag.operator;

/**
 * 通用算子可见的最小序列值协议（C1）。
 * 具体列式存储、视图选择和缓存实现仍留在 runtime 层。
 */
public interface OperatorSequence {
    int size();
    Object elementAt(int index);
    OperatorSequence filterByColumn(String column, Object value);
}
