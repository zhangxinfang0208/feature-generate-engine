package com.example.featuredag.runtime;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/** 通用序列等值索引：按 SequenceValue 的逻辑选择范围把 key 映射为 baseIndex 数组。 */
public final class SequenceKeyIndex {
    private SequenceKeyIndex() {}

    public static IndexValue build(
            SequenceValue sequence,
            SequenceKeyExtractor extractor,
            UnaryOperator<Object> keyNormalizer) {
        Map<Object, IntAccumulator> positions = new LinkedHashMap<>();
        for (int logicalIndex = 0; logicalIndex < sequence.size(); logicalIndex++) {
            int baseIndex = sequence.baseIndexAt(logicalIndex);
            // 索引 key 与查询 key 使用同一归一化器，保证类型与 null 语义对称。
            Object key = keyNormalizer.apply(extractor.extract(sequence.baseBlock(), baseIndex));
            positions.computeIfAbsent(key, ignored -> new IntAccumulator()).add(baseIndex);
        }
        // 无装箱累加，避免 List<Integer> 装箱再 stream 拆箱的双重开销；IndexValue.owned 跳过
        // 二次防御拷贝——这里的数组本就是刚构建、后续不再持有引用的独占数组。
        Map<Object, int[]> result = new LinkedHashMap<>();
        for (Map.Entry<Object, IntAccumulator> entry : positions.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toArray());
        }
        return IndexValue.owned(result);
    }

    /** 无装箱的可增长 int 缓冲区，仅供本类内部按 key 累积 baseIndex 使用。 */
    private static final class IntAccumulator {
        private int[] values = new int[4];
        private int size;

        void add(int value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            values[size++] = value;
        }

        int[] toArray() {
            return size == values.length ? values : Arrays.copyOf(values, size);
        }
    }
}
