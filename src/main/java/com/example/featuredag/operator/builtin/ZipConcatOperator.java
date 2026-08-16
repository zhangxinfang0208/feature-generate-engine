package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.operator.BatchOperatorCall;
import com.example.featuredag.operator.BatchOperatorKernel;
import com.example.featuredag.operator.BatchOperatorResult;
import com.example.featuredag.operator.ListBatchColumn;
import com.example.featuredag.operator.OperatorInputMetadata;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 逐位置拼接两个或更多等长序列，可用末尾对象字面量覆盖分隔符。 */
public final class ZipConcatOperator extends AbstractBuiltinOperator
        implements BatchOperatorKernel {
    public ZipConcatOperator() {
        super("zip_concat", 2, Integer.MAX_VALUE, true, true);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        // 事件元素无既定字符串契约：构图期即拒绝 EVENT_SEQUENCE，运行时元素检查为防御。
        for (OperatorInputMetadata input : inputs) {
            if (input.outputType() == DataType.EVENT_SEQUENCE) {
                throw new IllegalArgumentException(
                        "zip_concat does not support event sequence elements");
            }
        }
        return OperatorSupport.fixedInference(inputs, DataType.STRING, ValueShape.SEQUENCE);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        int sequenceCount = arguments.size();
        String delimiter = "#";
        Object last = arguments.get(arguments.size() - 1);
        // 最后一个参数只有在 OBJECT/Map 时才解释为配置；否则它仍是一条待拼接序列。
        if (last instanceof Map<?, ?>) {
            sequenceCount--;
            Map<?, ?> config = (Map<?, ?>) last;
            Object configured = config.get("delimiter");
            if (configured != null) delimiter = String.valueOf(configured);
        }
        if (sequenceCount < 2) {
            throw new IllegalArgumentException("zip_concat requires at least two sequences");
        }
        return zipSequences(arguments, sequenceCount, delimiter);
    }

    @Override
    public BatchOperatorResult evaluateBatch(BatchOperatorCall call) {
        List<Object> result = new ArrayList<Object>(call.rowCount());
        // 同组、同序列身份、同分隔符的组合只拼接一次，随后按 Batch 原行序复用结果。
        Map<ZipBatchKey, Object> values = new LinkedHashMap<ZipBatchKey, Object>();
        for (int rowIndex = 0; rowIndex < call.rowCount(); rowIndex++) {
            try {
                int sequenceCount = call.arguments().size();
                Object last = call.arguments().get(sequenceCount - 1).valueAt(rowIndex);
                String delimiter = "#";
                if (last instanceof Map<?, ?>) {
                    sequenceCount--;
                    Object configured = ((Map<?, ?>) last).get("delimiter");
                    if (configured != null) delimiter = String.valueOf(configured);
                }
                if (sequenceCount < 2) {
                    throw new IllegalArgumentException(
                            "zip_concat requires at least two sequences");
                }
                // 直接建数组、按引用交给 ZipBatchKey（本行独占，后续不再持有可变引用），
                // 比先建 List 再 toArray 少一次分配；命中缓存时完全不必再碰 zipSequences。
                Object[] sequenceValues = new Object[sequenceCount];
                for (int index = 0; index < sequenceCount; index++) {
                    sequenceValues[index] = call.arguments().get(index).valueAt(rowIndex);
                }
                ZipBatchKey key = new ZipBatchKey(
                        call.layout().groupIndexAt(rowIndex), sequenceValues, delimiter);
                Object value = values.get(key);
                if (value == null) {
                    value = zipSequences(Arrays.asList(sequenceValues), sequenceCount, delimiter);
                    values.put(key, value);
                }
                result.add(value);
            } catch (RuntimeException error) {
                throw OperatorSupport.batchFailure(rowIndex, error);
            }
        }
        return new BatchOperatorResult(ListBatchColumn.owned(result));
    }

    private List<String> zipSequences(
            List<Object> arguments,
            int sequenceCount,
            String delimiter) {
        List<Object> sequences = new ArrayList<Object>(sequenceCount);
        int size = -1;
        // 先统一验证所有序列等长，再进入按行拼接，避免生成一半结果后才发现形状错误。
        for (int index = 0; index < sequenceCount; index++) {
            Object sequence = arguments.get(index);
            int sequenceSize = OperatorSupport.sequenceSize(
                    sequence, name(), "sequence " + index);
            if (size < 0) size = sequenceSize;
            if (sequenceSize != size) {
                throw new IllegalArgumentException(
                        "zip_concat requires sequences of equal length; sequence " + index
                                + " has length " + sequenceSize + ", expected " + size);
            }
            sequences.add(sequence);
        }
        List<String> result = new ArrayList<String>(size);
        // 外层按逻辑位置、内层按输入序列，形成真正的 zip，而不是先串联各序列。
        for (int row = 0; row < size; row++) {
            StringBuilder joined = new StringBuilder();
            for (int column = 0; column < sequences.size(); column++) {
                Object element = OperatorSupport.sequenceElementAt(
                        sequences.get(column), row, name(), "sequence " + column);
                if (element instanceof Map<?, ?>) {
                    // 事件元素无既定字符串契约，拒绝拼接，避免把事件结构 dump 固化为特征值。
                    throw new IllegalArgumentException(
                            "zip_concat does not support event sequence elements"
                                    + " (column " + column + ", row " + row + ")");
                }
                if (column > 0) joined.append(delimiter);
                joined.append(String.valueOf(element));
            }
            result.add(joined.toString());
        }
        return OperatorSupport.immutableList(result);
    }

    private static final class ZipBatchKey {
        private final int groupIndex;
        private final Object[] sequences;
        private final String delimiter;
        private final int hashCode;

        private ZipBatchKey(int groupIndex, Object[] sequences, String delimiter) {
            this.groupIndex = groupIndex;
            // 序列用身份比较，既避免深比较成本，也使不同 SequenceView 的选择语义保持隔离；
            // 数组按引用持有，调用方保证是本行刚构建、后续不再持有可变引用的独占数组。
            this.sequences = sequences;
            this.delimiter = delimiter;
            int hash = 31 * groupIndex + delimiter.hashCode();
            for (Object sequence : this.sequences) {
                hash = 31 * hash + System.identityHashCode(sequence);
            }
            this.hashCode = hash;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof ZipBatchKey)) return false;
            ZipBatchKey other = (ZipBatchKey) value;
            if (groupIndex != other.groupIndex
                    || !delimiter.equals(other.delimiter)
                    || sequences.length != other.sequences.length) {
                return false;
            }
            for (int index = 0; index < sequences.length; index++) {
                if (sequences[index] != other.sequences[index]) return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
