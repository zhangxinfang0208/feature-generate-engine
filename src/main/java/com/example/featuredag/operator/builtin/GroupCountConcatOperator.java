package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 按值分组计数，并按配置顺序输出 {@code value + delimiter + count}。 */
public final class GroupCountConcatOperator extends AbstractBuiltinOperator {
    private static final String DEFAULT_DELIMITER = "#";
    private static final String FIRST_OCCURRENCE = "FIRST_OCCURRENCE";
    private static final String COUNT_DESC = "COUNT_DESC";

    public GroupCountConcatOperator() {
        super("group_count_concat", 1, 2, true, true);
    }

    @Override
    public List<String> parameterNames() {
        return Arrays.asList("sequence", "config");
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
        if (arguments.size() == 1) {
            return groupAndConcat(arguments.get(0), DEFAULT_DELIMITER, FIRST_OCCURRENCE);
        }
        Object rawConfig = arguments.get(1);
        Map<?, ?> config = asConfig(rawConfig);
        return groupAndConcat(
                arguments.get(0), delimiter(config), order(config));
    }

    private Map<?, ?> asConfig(Object rawConfig) {
        if (!(rawConfig instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(
                    "group_count_concat expects an object config as its second argument");
        }
        return (Map<?, ?>) rawConfig;
    }

    private String delimiter(Map<?, ?> config) {
        Object configured = config.get("delimiter");
        return configured == null ? DEFAULT_DELIMITER : String.valueOf(configured);
    }

    private String order(Map<?, ?> config) {
        Object configured = config.get("order");
        String result = configured == null ? FIRST_OCCURRENCE : String.valueOf(configured);
        if (!FIRST_OCCURRENCE.equals(result) && !COUNT_DESC.equals(result)) {
            throw new IllegalArgumentException(
                    "group_count_concat order must be FIRST_OCCURRENCE or COUNT_DESC, got: "
                            + result);
        }
        return result;
    }

    private List<String> groupAndConcat(
            Object rawSequence,
            String delimiter,
            String order) {
        int size = OperatorSupport.sequenceSize(rawSequence, name(), "sequence");
        Map<Object, Integer> counts = new LinkedHashMap<Object, Integer>();
        for (int index = 0; index < size; index++) {
            Object value = OperatorSupport.sequenceElementAt(
                    rawSequence, index, name(), "sequence");
            if (value instanceof Map<?, ?>) {
                throw new IllegalArgumentException(
                        "group_count_concat does not support event sequence elements"
                                + " (index " + index + ")");
            }
            Integer count = counts.get(value);
            counts.put(value, count == null ? 1 : count + 1);
        }

        List<Map.Entry<Object, Integer>> ordered =
                new ArrayList<Map.Entry<Object, Integer>>(counts.entrySet());
        if (COUNT_DESC.equals(order)) {
            // Collections.sort 是稳定排序；同频元素保持 LinkedHashMap 的首次出现顺序。
            Collections.sort(ordered, new Comparator<Map.Entry<Object, Integer>>() {
                @Override
                public int compare(
                        Map.Entry<Object, Integer> left,
                        Map.Entry<Object, Integer> right) {
                    return Integer.compare(right.getValue(), left.getValue());
                }
            });
        }

        List<String> result = new ArrayList<String>(ordered.size());
        for (Map.Entry<Object, Integer> entry : ordered) {
            result.add(String.valueOf(entry.getKey()) + delimiter + entry.getValue());
        }
        return OperatorSupport.immutableList(result);
    }
}
