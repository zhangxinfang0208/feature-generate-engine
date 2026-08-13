package com.example.featuredag.runtime;

import com.example.featuredag.operator.OperatorSequence;
import com.example.featuredag.physical.SequenceViewInputMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;

/** Kernel 调用边界的序列视图适配器；物化严格遵守逻辑视图的长度和顺序。 */
final class SequenceViewArgumentAdapter {
    private SequenceViewArgumentAdapter() {}

    static Object adapt(Object value, SequenceViewInputMode mode) {
        return adapt(value, mode, new IdentityHashMap<OperatorSequence, Object>());
    }

    static Object adapt(
            Object value,
            SequenceViewInputMode mode,
            IdentityHashMap<OperatorSequence, Object> materialized) {
        if (mode == SequenceViewInputMode.DIRECT || !(value instanceof OperatorSequence)) {
            return value;
        }
        OperatorSequence sequence = (OperatorSequence) value;
        Object cached = materialized.get(sequence);
        if (cached != null) return cached;
        List<Object> result = new ArrayList<Object>(sequence.size());
        for (int index = 0; index < sequence.size(); index++) {
            result.add(sequence.elementAt(index));
        }
        Object immutable = Collections.unmodifiableList(result);
        materialized.put(sequence, immutable);
        return immutable;
    }
}
