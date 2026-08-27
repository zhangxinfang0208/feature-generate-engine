package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.BatchOperatorCall;
import com.example.featuredag.operator.BatchOperatorResult;
import com.example.featuredag.operator.BatchOperatorResultBuilder;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorSequence;
import com.example.featuredag.operator.RecoverableBatchOperatorKernel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 对序列元素按 equals/hashCode 去重并返回基数，兼容普通集合和零拷贝 OperatorSequence 视图。 */
public final class CountDistinctOperator extends AbstractBuiltinOperator
        implements RecoverableBatchOperatorKernel {
    public CountDistinctOperator() {
        super("count_distinct", 1, 1, true, true);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.fixedInference(inputs, DataType.INT, ValueShape.SCALAR);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        return count(arguments.get(0));
    }

    @Override
    public BatchOperatorResult evaluateBatch(BatchOperatorCall call) {
        BatchOperatorResultBuilder result = new BatchOperatorResultBuilder(call.rowCount());
        // 请求组和序列身份共同构成批内复用键；同一序列只扫描一次，输出仍逐行追加。
        Map<OperatorSupport.IdentityBatchKey, Integer> counts =
                new LinkedHashMap<OperatorSupport.IdentityBatchKey, Integer>();
        for (int rowIndex = 0; rowIndex < call.rowCount(); rowIndex++) {
            Object sequence = call.arguments().get(0).valueAt(rowIndex);
            int groupIndex = call.layout().groupIndexAt(rowIndex);
            try {
                OperatorSupport.IdentityBatchKey key = OperatorSupport.identityBatchKey(
                        groupIndex, sequence);
                Integer count = counts.get(key);
                if (count == null) {
                    count = count(sequence);
                    counts.put(key, count);
                }
                result.addValue(count);
            } catch (RuntimeException error) {
                result.addFailure(error);
            }
        }
        return result.build();
    }

    private static int count(Object sequence) {
        // LinkedHashSet 的顺序不影响最终计数，但使行为和调试过程保持确定。
        LinkedHashSet<Object> values = new LinkedHashSet<Object>();
        if (sequence instanceof OperatorSequence) {
            OperatorSequence value = (OperatorSequence) sequence;
            for (int index = 0; index < value.size(); index++) {
                values.add(value.elementAt(index));
            }
        } else if (sequence instanceof Collection<?>) {
            values.addAll((Collection<?>) sequence);
        } else if (sequence != null && sequence.getClass().isArray()) {
            // 与 get_seq_length 的数组口径对齐：数组序列同样按元素去重
            int length = java.lang.reflect.Array.getLength(sequence);
            for (int index = 0; index < length; index++) {
                values.add(java.lang.reflect.Array.get(sequence, index));
            }
        } else {
            throw new IllegalArgumentException(
                    "count_distinct expects a sequence, got: "
                            + OperatorSupport.typeName(sequence));
        }
        return values.size();
    }
}
