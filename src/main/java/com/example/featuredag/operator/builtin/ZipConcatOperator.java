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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                List<Object> sequences = new ArrayList<Object>(sequenceCount);
                for (int index = 0; index < sequenceCount; index++) {
                    sequences.add(call.arguments().get(index).valueAt(rowIndex));
                }
                ZipBatchKey key = new ZipBatchKey(
                        call.layout().groupIndexAt(rowIndex), sequences, delimiter);
                Object value = values.get(key);
                if (value == null) {
                    value = zipSequences(sequences, sequenceCount, delimiter);
                    values.put(key, value);
                }
                result.add(value);
            } catch (RuntimeException error) {
                throw OperatorSupport.batchFailure(rowIndex, error);
            }
        }
        return new BatchOperatorResult(new ListBatchColumn(result));
    }

    private List<String> zipSequences(
            List<Object> arguments,
            int sequenceCount,
            String delimiter) {
        List<Object> sequences = new ArrayList<Object>(sequenceCount);
        int size = -1;
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

        private ZipBatchKey(int groupIndex, List<Object> sequences, String delimiter) {
            this.groupIndex = groupIndex;
            this.sequences = sequences.toArray(new Object[sequences.size()]);
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
