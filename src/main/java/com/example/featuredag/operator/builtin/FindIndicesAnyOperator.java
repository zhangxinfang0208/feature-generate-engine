package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 返回源序列中命中任一目标值的全部逻辑下标，并保持源序列顺序。 */
public final class FindIndicesAnyOperator extends AbstractBuiltinOperator {
    public FindIndicesAnyOperator() {
        super("find_indices_any", 2, 2, true, true);
    }

    @Override
    public List<String> parameterNames() {
        return Arrays.asList("sequence", "targets");
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        for (int index = 0; index < inputs.size(); index++) {
            if (inputs.get(index).valueShape() != ValueShape.SEQUENCE) {
                throw new IllegalArgumentException(
                        "find_indices_any expects sequence input at position " + index);
            }
        }
        return OperatorSupport.fixedInference(inputs, DataType.INT, ValueShape.SEQUENCE);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        Object source = arguments.get(0);
        Object rawTargets = arguments.get(1);
        int targetCount = OperatorSupport.sequenceSize(rawTargets, name(), "targets");
        Set<Object> targets = new LinkedHashSet<Object>();
        for (int index = 0; index < targetCount; index++) {
            targets.add(OperatorSupport.sequenceElementAt(
                    rawTargets, index, name(), "targets"));
        }

        int sourceSize = OperatorSupport.sequenceSize(source, name(), "sequence");
        List<Integer> result = new ArrayList<Integer>();
        for (int index = 0; index < sourceSize; index++) {
            Object value = OperatorSupport.sequenceElementAt(
                    source, index, name(), "sequence");
            if (targets.contains(value)) result.add(Integer.valueOf(index));
        }
        return OperatorSupport.immutableList(result);
    }
}
