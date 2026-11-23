package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.BatchOperatorCall;
import com.example.featuredag.operator.BatchOperatorResult;
import com.example.featuredag.operator.BatchOperatorResultBuilder;
import com.example.featuredag.operator.BorrowedArgumentsKernel;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorSequence;
import com.example.featuredag.operator.RecoverableBatchOperatorKernel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.RandomAccess;

/** 对序列元素按 equals/hashCode 去重并返回基数，兼容普通集合和零拷贝 OperatorSequence 视图。 */
public final class CountDistinctOperator extends AbstractBuiltinOperator
        implements BorrowedArgumentsKernel, RecoverableBatchOperatorKernel {
    /**
     * 不超过这个长度的序列用线性扫描去重：元素很少时比哈希集合更快，
     * 也不必为每个元素分配节点；批内各行共用同一块暂存区。
     */
    private static final int LINEAR_SCAN_LIMIT = 16;

    public CountDistinctOperator() {
        super("count_distinct", 1, 1, true, true);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return OperatorSupport.fixedInference(inputs, DataType.INT, ValueShape.SCALAR);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        return count(arguments.get(0), new Object[LINEAR_SCAN_LIMIT]);
    }

    @Override
    public BatchOperatorResult evaluateBatch(BatchOperatorCall call) {
        BatchOperatorResultBuilder result = new BatchOperatorResultBuilder(call.rowCount());
        // 请求组和序列身份共同构成批内复用键；同一序列只扫描一次，输出仍逐行追加。
        OperatorSupport.IdentityBatchCache<Integer> counts = new OperatorSupport.IdentityBatchCache<Integer>();
        Object[] scratch = new Object[LINEAR_SCAN_LIMIT];
        for (int rowIndex = 0; rowIndex < call.rowCount(); rowIndex++) {
            Object sequence = call.arguments().get(0).valueAt(rowIndex);
            int groupIndex = call.layout().groupIndexAt(rowIndex);
            try {
                Integer count = counts.get(groupIndex, sequence);
                if (count == null) {
                    count = Integer.valueOf(count(sequence, scratch));
                    counts.put(groupIndex, sequence, count);
                }
                result.addValue(count);
            } catch (RuntimeException error) {
                result.addFailure(error);
            }
        }
        return result.build();
    }

    private static int count(Object sequence, Object[] scratch) {
        if (sequence instanceof OperatorSequence) {
            OperatorSequence value = (OperatorSequence) sequence;
            int size = value.size();
            if (size <= LINEAR_SCAN_LIMIT) {
                int distinct = 0;
                for (int index = 0; index < size; index++) {
                    distinct = addDistinct(scratch, distinct, value.elementAt(index));
                }
                return distinct;
            }
            LinkedHashSet<Object> values = new LinkedHashSet<Object>();
            for (int index = 0; index < size; index++) {
                values.add(value.elementAt(index));
            }
            return values.size();
        }
        if (sequence instanceof List<?> && sequence instanceof RandomAccess) {
            List<?> list = (List<?>) sequence;
            int size = list.size();
            if (size <= LINEAR_SCAN_LIMIT) {
                int distinct = 0;
                for (int index = 0; index < size; index++) {
                    distinct = addDistinct(scratch, distinct, list.get(index));
                }
                return distinct;
            }
        } else if (sequence != null && sequence.getClass().isArray()) {
            // 与 get_seq_length 的数组口径对齐：数组序列同样按元素去重
            int length = java.lang.reflect.Array.getLength(sequence);
            if (length <= LINEAR_SCAN_LIMIT) {
                int distinct = 0;
                for (int index = 0; index < length; index++) {
                    distinct = addDistinct(scratch, distinct, java.lang.reflect.Array.get(sequence, index));
                }
                return distinct;
            }
        } else if (!(sequence instanceof Collection<?>)) {
            throw new IllegalArgumentException(
                    "count_distinct expects a sequence, got: "
                            + OperatorSupport.typeName(sequence));
        }
        // LinkedHashSet 的顺序不影响最终计数，但使行为和调试过程保持确定。
        LinkedHashSet<Object> values = new LinkedHashSet<Object>();
        if (sequence instanceof Collection<?>) {
            values.addAll((Collection<?>) sequence);
        } else {
            int length = java.lang.reflect.Array.getLength(sequence);
            for (int index = 0; index < length; index++) {
                values.add(java.lang.reflect.Array.get(sequence, index));
            }
        }
        return values.size();
    }

    /** 线性去重：元素已出现时原样返回个数，否则记入暂存区并返回加一后的个数。 */
    private static int addDistinct(Object[] seen, int distinct, Object element) {
        for (int index = 0; index < distinct; index++) {
            if (Objects.equals(seen[index], element)) return distinct;
        }
        seen[distinct] = element;
        return distinct + 1;
    }
}
