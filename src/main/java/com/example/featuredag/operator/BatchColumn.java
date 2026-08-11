package com.example.featuredag.operator;

/** 只读 Batch 列；常量和 request-to-candidate 广播可以通过虚拟列实现而无需复制。 */
public interface BatchColumn {
    int size();

    Object valueAt(int rowIndex);
}
