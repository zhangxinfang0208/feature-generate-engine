package com.example.featuredag.api;

import com.example.featuredag.config.FeatureOutputDescriptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一族输出行（共享行或候选行）的键布局（C9）：同一请求里这些行的键集合与顺序完全相同，
 * 键数组在所有行之间共享，每行只持有自己的值数组。
 * 布局不可变且线程安全，可跨请求复用；按键查询用的下标表在第一次查询时构建一次。
 */
final class FeatureOutputLayout {
    /** 没有任何键的行共用的空值数组。 */
    private static final List<?>[] NO_VALUES = new List<?>[0];

    private final String[] keys;
    /** 输出下标 → 本族内的值下标；不属于本族的输出为 -1。 */
    private final int[] slotByOutput;
    private volatile Map<String, Integer> indexByStoreName;

    private FeatureOutputLayout(String[] keys, int[] slotByOutput) {
        this.keys = keys;
        this.slotByOutput = slotByOutput;
    }

    /**
     * 为一族输出建立布局。重复的 storeName 与逐行 LinkedHashMap 的 put 语义一致：
     * 键只按首次出现的位置排序，后写入的输出覆盖先写入的值。
     */
    static FeatureOutputLayout forFamily(
            List<FeatureOutputDescriptor> outputs,
            boolean[] candidateLevel,
            boolean candidateFamily) {
        Map<String, Integer> firstSlotByKey = new LinkedHashMap<>();
        int[] slotByOutput = new int[outputs.size()];
        for (int outputIndex = 0; outputIndex < outputs.size(); outputIndex++) {
            slotByOutput[outputIndex] = -1;
            if (candidateLevel[outputIndex] != candidateFamily) continue;
            String storeName = outputs.get(outputIndex).storeName();
            Integer existing = firstSlotByKey.get(storeName);
            int slot = existing == null ? firstSlotByKey.size() : existing;
            slotByOutput[outputIndex] = slot;
            firstSlotByKey.put(storeName, slot);
        }
        return new FeatureOutputLayout(firstSlotByKey.keySet().toArray(new String[0]), slotByOutput);
    }

    /** 键 → 值下标；不是本族的键时返回 null。 */
    Integer indexOf(Object key) {
        Map<String, Integer> index = indexByStoreName;
        if (index == null) {
            synchronized (this) {
                index = indexByStoreName;
                if (index == null) {
                    Map<String, Integer> built = new HashMap<>();
                    for (int slot = 0; slot < keys.length; slot++) {
                        built.put(keys[slot], slot);
                    }
                    indexByStoreName = index = built;
                }
            }
        }
        return index.get(key);
    }

    /** 本族第 slot 个键，按首次出现的顺序排列。 */
    String key(int slot) {
        return keys[slot];
    }

    /** 按本布局新建 rowCount 行值数组；只在当前请求线程内使用。 */
    Rows newRows(int rowCount) {
        return new Rows(this, rowCount);
    }

    /** 一个请求内本族的全部输出行。 */
    static final class Rows {
        private final FeatureOutputLayout layout;
        private final List<?>[][] values;

        private Rows(FeatureOutputLayout layout, int rowCount) {
            this.layout = layout;
            this.values = new List<?>[rowCount][];
            List<?>[] noKeys = layout.keys.length == 0 ? NO_VALUES : null;
            for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
                values[rowIndex] = noKeys != null ? noKeys : new List<?>[layout.keys.length];
            }
        }

        /** 写入某个输出在某一行上的值；该输出不属于本族时不做任何事。 */
        void set(int outputIndex, int rowIndex, List<?> value) {
            int slot = layout.slotByOutput[outputIndex];
            if (slot >= 0) values[rowIndex][slot] = value;
        }

        /** 把同一个编码结果写入该输出在本族的所有行。 */
        void setAll(int outputIndex, List<?> value) {
            int slot = layout.slotByOutput[outputIndex];
            if (slot < 0) return;
            for (List<?>[] row : values) row[slot] = value;
        }

        List<Map<String, List<?>>> toMaps() {
            List<Map<String, List<?>>> maps = new ArrayList<>(values.length);
            if (layout.keys.length == 0) {
                // 没有键的行内容完全相同，共用一个空 Map。
                SharedKeyOutputMap empty = new SharedKeyOutputMap(layout, NO_VALUES);
                for (int rowIndex = 0; rowIndex < values.length; rowIndex++) maps.add(empty);
                return maps;
            }
            for (List<?>[] row : values) maps.add(new SharedKeyOutputMap(layout, row));
            return maps;
        }
    }
}
