package com.example.featuredag.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ExternalValueMaterializer {
    public Object materialize(ValueHandle handle) {
        Objects.requireNonNull(handle, "handle");
        if (handle instanceof SequenceValue sequence) return materializeSequence(sequence);
        if (handle instanceof ListSequenceValue sequence) {
            return sequence.values().stream().map(this::materializeRaw).toList();
        }
        if (handle instanceof ScalarValue scalar) return materializeRaw(scalar.value());
        if (handle instanceof CandidateVectorValue vector) {
            return vector.values().stream().map(this::materializeRaw).toList();
        }
        if (handle instanceof OfflineBatchValue batch) {
            return batch.values().stream().map(this::materializeRaw).toList();
        }
        if (handle instanceof RequestBatchValue batch) {
            return batch.values().stream().map(this::materializeRaw).toList();
        }
        if (handle instanceof CandidateBatchValue batch) {
            return batch.values().stream().map(this::materializeRaw).toList();
        }
        throw new IllegalArgumentException(
                "Unsupported public output handle: " + handle.getClass().getName());
    }

    public Object materializeRaw(Object value) {
        if (value instanceof ValueHandle handle) return materialize(handle);
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(entry.getKey(), materializeRaw(entry.getValue()));
            }
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::materializeRaw).toList();
        }
        return value;
    }

    /**
     * 公共输出边界只物化顶层序列前缀（C1/C6）；保留元素内部的 Map/List 仍完整递归物化。
     * 长度限制不进入 DAG，也不截断上游中间值或批内单个元素的嵌套序列。
     */
    public Object materializeRaw(Object value, int maxSequenceLength) {
        if (maxSequenceLength < 0) {
            throw new IllegalArgumentException("maxSequenceLength must not be negative");
        }
        if (value instanceof SequenceValue sequence) {
            return materializeSequence(sequence, Math.min(sequence.size(), maxSequenceLength));
        }
        if (value instanceof ScalarValue scalar) {
            return materializeRaw(scalar.value(), maxSequenceLength);
        }
        if (value instanceof ListSequenceValue sequence) {
            return materializeList(sequence.values(), maxSequenceLength);
        }
        if (value instanceof CandidateVectorValue vector) {
            return materializeList(vector.values(), maxSequenceLength);
        }
        if (value instanceof OfflineBatchValue batch) {
            return materializeList(batch.values(), maxSequenceLength);
        }
        if (value instanceof RequestBatchValue batch) {
            return materializeList(batch.values(), maxSequenceLength);
        }
        if (value instanceof CandidateBatchValue batch) {
            return materializeList(batch.values(), maxSequenceLength);
        }
        if (value instanceof List<?> list) return materializeList(list, maxSequenceLength);
        return materializeRaw(value);
    }

    private List<Object> materializeList(List<?> values, int maxLength) {
        int size = Math.min(values.size(), maxLength);
        List<Object> result = new ArrayList<>(size);
        var iterator = values.iterator();
        for (int index = 0; index < size; index++) {
            result.add(materializeRaw(iterator.next()));
        }
        return Collections.unmodifiableList(result);
    }

    private List<Map<String, Object>> materializeSequence(SequenceValue sequence) {
        return materializeSequence(sequence, sequence.size());
    }

    private List<Map<String, Object>> materializeSequence(SequenceValue sequence, int size) {
        List<Map<String, Object>> events = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            // 事件行在 SequenceBlock 构造时已防御拷贝并不可变化，直接透传属性全集（兼容超集契约）。
            events.add(sequence.baseBlock().rowAtBaseIndex(sequence.baseIndexAt(index)));
        }
        return Collections.unmodifiableList(events);
    }
}
