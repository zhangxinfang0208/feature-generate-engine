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
        if (handle instanceof ScalarValue scalar) return materializeRaw(scalar.value());
        if (handle instanceof CandidateVectorValue vector) {
            return vector.values().stream().map(this::materializeRaw).toList();
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

    private List<Map<String, Object>> materializeSequence(SequenceValue sequence) {
        List<Map<String, Object>> events = new ArrayList<>(sequence.size());
        SequenceBlock base = sequence.baseBlock();
        for (int index = 0; index < sequence.size(); index++) {
            SequenceEvent event = base.eventAtBaseIndex(sequence.baseIndexAt(index));
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("itemId", event.itemId());
            values.put("industryId", event.industryId());
            values.put("timestamp", event.timestamp());
            values.put("eventType", event.eventType());
            values.put("value", event.value());
            events.add(Collections.unmodifiableMap(values));
        }
        return List.copyOf(events);
    }
}
