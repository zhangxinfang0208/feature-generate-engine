package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 按 key 集合过滤事件序列，保持源序列顺序并保留重复事件。 */
public final class HitOperator extends AbstractBuiltinOperator {
    private static final String KEY_FIELD = "key";

    public HitOperator() {
        super("hit", 2, 2, true, true);
    }

    @Override
    public List<String> parameterNames() {
        return Arrays.asList("seq_kv", "seq_key");
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        OperatorInputMetadata events = inputs.get(0);
        if (events.outputType() != DataType.EVENT_SEQUENCE
                || events.valueShape() != ValueShape.SEQUENCE) {
            throw new IllegalArgumentException(
                    "hit expects EVENT_SEQUENCE/SEQUENCE as its first argument");
        }
        OperatorInputMetadata keys = inputs.get(1);
        if (keys.outputType() != DataType.STRING
                || keys.valueShape() != ValueShape.SEQUENCE) {
            throw new IllegalArgumentException(
                    "hit expects STRING/SEQUENCE as its second argument");
        }
        return OperatorSupport.passThroughInference(inputs, 0);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        Object eventSequence = arguments.get(0);
        Object keySequence = arguments.get(1);
        int keyCount = OperatorSupport.sequenceSize(keySequence, name(), "key sequence");
        Set<String> keys = new LinkedHashSet<String>();
        for (int index = 0; index < keyCount; index++) {
            Object rawKey = OperatorSupport.sequenceElementAt(
                    keySequence, index, name(), "key sequence");
            if (!(rawKey instanceof String)) {
                throw new IllegalArgumentException(
                        "hit query key at index " + index + " must be STRING, got: "
                                + OperatorSupport.typeName(rawKey));
            }
            keys.add((String) rawKey);
        }

        int eventCount = OperatorSupport.sequenceSize(
                eventSequence, name(), "event sequence");
        List<Object> result = new ArrayList<Object>();
        for (int index = 0; index < eventCount; index++) {
            Object rawEvent = OperatorSupport.sequenceElementAt(
                    eventSequence, index, name(), "event sequence");
            if (!(rawEvent instanceof Map<?, ?>)) {
                throw new IllegalArgumentException(
                        "hit event at index " + index + " must be Map, got: "
                                + OperatorSupport.typeName(rawEvent));
            }
            Map<?, ?> event = (Map<?, ?>) rawEvent;
            if (!event.containsKey(KEY_FIELD)) {
                throw new IllegalArgumentException(
                        "hit event at index " + index + " is missing key field '"
                                + KEY_FIELD + "'");
            }
            Object eventKey = event.get(KEY_FIELD);
            if (!(eventKey instanceof String)) {
                throw new IllegalArgumentException(
                        "hit event key at index " + index + " must be STRING, got: "
                                + OperatorSupport.typeName(eventKey));
            }
            if (keys.contains(eventKey)) result.add(event);
        }
        return OperatorSupport.immutableList(result);
    }
}
