package com.example.featuredag.operator;

/** Batch 行布局；在线候选行可通过它映射回所属请求组与组内下标。 */
public interface BatchLayout {
    BatchDomain domain();

    int rowCount();

    int groupIndexAt(int rowIndex);

    int indexInGroupAt(int rowIndex);
}
