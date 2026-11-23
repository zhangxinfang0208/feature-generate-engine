package com.example.featuredag.runtime;

import com.example.featuredag.operator.OperatorResultList;

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
            return materializeList(sequence.values(), Integer.MAX_VALUE);
        }
        if (handle instanceof ScalarValue scalar) return materializeRaw(scalar.value());
        if (handle instanceof CandidateVectorValue vector) {
            return materializeList(vector.values(), Integer.MAX_VALUE);
        }
        if (handle instanceof OfflineBatchValue batch) {
            return materializeList(batch.values(), Integer.MAX_VALUE);
        }
        if (handle instanceof RequestBatchValue batch) {
            return materializeList(batch.values(), Integer.MAX_VALUE);
        }
        if (handle instanceof CandidateBatchValue batch) {
            return materializeList(batch.values(), Integer.MAX_VALUE);
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
            return materializeList(list, Integer.MAX_VALUE);
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
        // C1/C6：引擎独占且元素均为标量的不可变列表直接作为输出返回，不再复制。
        // 借用调用方容器的视图、含 Map/List/值句柄元素的列表仍按原路径递归物化。
        if (size == values.size() && isDetachedScalarList(values)) {
            @SuppressWarnings("unchecked")
            List<Object> detached = (List<Object>) values;
            return detached;
        }
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

    /**
     * 是否为引擎独占（不引用调用方可变容器）的不可变列表，且元素都不需要递归物化。
     * 只有这样的列表才允许直接进入公共输出。
     */
    private static boolean isDetachedScalarList(List<?> values) {
        boolean detached = values instanceof OperatorResultList<?>
                || values instanceof FrozenList<?> && ((FrozenList<?>) values).isDetached();
        if (!detached) return false;
        for (Object element : values) {
            if (element instanceof Map || element instanceof List || element instanceof ValueHandle) return false;
        }
        return true;
    }

    private List<Map<String, Object>> materializeSequence(SequenceValue sequence, int size) {
        if (sequence instanceof SequenceBlock block && size == block.size()) {
            // 未过滤、未截断的事件序列：块内的不可变事件列表本身就是输出内容，直接返回。
            return block.eventsView();
        }
        List<Map<String, Object>> events = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            // 事件行在 SequenceBlock 构造时已防御拷贝并不可变化，直接透传属性全集（兼容超集契约）。
            events.add(sequence.baseBlock().rowAtBaseIndex(sequence.baseIndexAt(index)));
        }
        return Collections.unmodifiableList(events);
    }
}
