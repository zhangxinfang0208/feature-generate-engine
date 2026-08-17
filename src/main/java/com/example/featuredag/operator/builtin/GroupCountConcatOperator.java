package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.operator.OperatorSequence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按值分组计数，并按首次出现顺序输出 {@code value + delimiter + count}。
 * 可选配置对象只接受字符串类型的 {@code delimiter}，默认值为 {@code #}。
 */
public final class GroupCountConcatOperator extends AbstractBuiltinOperator {
    private static final String DEFAULT_DELIMITER = "#";
    private static final String DELIMITER_KEY = "delimiter";
    private static final List<String> PARAMETER_NAMES = Collections.unmodifiableList(
            Arrays.asList("sequence", "config"));

    public GroupCountConcatOperator() {
        super("group_count_concat", 1, 2, true, true);
    }

    @Override
    public List<String> parameterNames() {
        return PARAMETER_NAMES;
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        OperatorInputMetadata sequence = inputs.get(0);
        if (sequence.valueShape() != ValueShape.SEQUENCE) {
            throw new IllegalArgumentException(
                    "group_count_concat expects a sequence as its first argument");
        }
        if (sequence.outputType() == DataType.EVENT_SEQUENCE) {
            throw new IllegalArgumentException(
                    "group_count_concat does not support event sequence elements");
        }
        if (inputs.size() == 2 && inputs.get(1).valueShape() != ValueShape.OBJECT) {
            throw new IllegalArgumentException(
                    "group_count_concat expects an object config as its second argument");
        }
        return OperatorSupport.fixedInference(inputs, DataType.STRING, ValueShape.SEQUENCE);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        String delimiter = arguments.size() == 1
                ? DEFAULT_DELIMITER
                : delimiter(arguments.get(1));
        return groupAndConcat(arguments.get(0), delimiter);
    }

    private static String delimiter(Object rawConfig) {
        if (!(rawConfig instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(
                    "group_count_concat expects an object config as its second argument");
        }
        Map<?, ?> config = (Map<?, ?>) rawConfig;
        for (Object key : config.keySet()) {
            if (!DELIMITER_KEY.equals(key)) {
                throw new IllegalArgumentException(
                        "group_count_concat config contains unknown key: " + key);
            }
        }
        if (!config.containsKey(DELIMITER_KEY)) return DEFAULT_DELIMITER;
        Object configured = config.get(DELIMITER_KEY);
        if (!(configured instanceof String)) {
            throw new IllegalArgumentException(
                    "group_count_concat delimiter must be a string");
        }
        return (String) configured;
    }

    private List<String> groupAndConcat(Object rawSequence, String delimiter) {
        int size = OperatorSupport.sequenceSize(rawSequence, name(), "sequence");
        Map<Object, Integer> counts = new LinkedHashMap<Object, Integer>();
        for (int index = 0; index < size; index++) {
            Object value = OperatorSupport.sequenceElementAt(
                    rawSequence, index, name(), "sequence");
            if (value instanceof Map<?, ?>
                    || value instanceof Iterable<?>
                    || value instanceof OperatorSequence
                    || (value != null && value.getClass().isArray())) {
                throw new IllegalArgumentException(
                        "group_count_concat requires scalar sequence elements; element at index "
                                + index + " is " + OperatorSupport.typeName(value));
            }
            Integer count = counts.get(value);
            counts.put(value, count == null ? 1 : count + 1);
        }

        List<String> result = new ArrayList<String>(counts.size());
        for (Map.Entry<Object, Integer> entry : counts.entrySet()) {
            result.add(String.valueOf(entry.getKey()) + delimiter + entry.getValue());
        }
        return OperatorSupport.immutableList(result);
    }
}
