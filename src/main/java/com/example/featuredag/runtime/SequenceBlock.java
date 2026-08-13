package com.example.featuredag.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable row-based base sequence.
 *
 * 序列底层块：按行存储不可变事件 Map（事件属性全集由输入边界决定，兼容超集契约），
 * 是所有序列视图（SequenceView）共享的数据载体；handleKey 用于缓存标识。
 */
public final class SequenceBlock implements SequenceValue {
    private final String sequenceId;
    private final long version;
    private final List<Map<String, Object>> events;

    public SequenceBlock(
            String sequenceId,
            long version,
            List<? extends Map<?, ?>> events) {
        this.sequenceId = Objects.requireNonNull(sequenceId, "sequenceId");
        this.version = version;
        List<Map<String, Object>> copied = new ArrayList<>(events.size());
        for (int index = 0; index < events.size(); index++) {
            copied.add(immutableEvent(events.get(index), index));
        }
        this.events = Collections.unmodifiableList(copied);
    }

    public String sequenceId() { return sequenceId; }
    public long version() { return version; }
    public String handleKey() { return sequenceId + "@" + version; }
    @Override public int size() { return events.size(); }
    @Override public SequenceBlock baseBlock() { return this; }
    @Override public int baseIndexAt(int logicalIndex) { return logicalIndex; }

    public Map<String, Object> rowAtBaseIndex(int index) {
        if (index < 0 || index >= size()) throw new IndexOutOfBoundsException(index);
        return events.get(index);
    }

    public Object columnValueAt(String column, int baseIndex) {
        Objects.requireNonNull(column, "column");
        return rowAtBaseIndex(baseIndex).get(column);
    }

    private static Map<String, Object> immutableEvent(Map<?, ?> event, int index) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : event.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException(
                        "SequenceBlock event keys must be strings at index " + index
                                + ", got: " + typeName(entry.getKey()));
            }
            copy.put((String) entry.getKey(), immutableValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * 事件值深度防御复制：Map/List 递归复制为不可变容器，其余类型按标量透传。
     * 调用方不得传入可变业务对象（自定义类型不递归，公共契约要求）。
     */
    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                copy.put(immutableValue(entry.getKey()), immutableValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?>) {
            List<Object> copy = new ArrayList<>(((List<?>) value).size());
            for (Object element : (List<?>) value) copy.add(immutableValue(element));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }
}
