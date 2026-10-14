package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 将序列中的每个数值向量与目标向量计算余弦相似度，保留原顺序。 */
public final class CosSimSeqOperator extends AbstractBuiltinOperator {
    public CosSimSeqOperator() {
        super("cos_sim_seq", 2, 2, true, true);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        if (inputs.get(0).outputType() != DataType.OBJECT
                || inputs.get(0).valueShape() != ValueShape.SEQUENCE) {
            throw new IllegalArgumentException("cos_sim_seq expects OBJECT + SEQUENCE for emb_seq");
        }
        if (inputs.get(1).outputType() != DataType.DOUBLE
                || inputs.get(1).valueShape() != ValueShape.SEQUENCE) {
            throw new IllegalArgumentException("cos_sim_seq expects DOUBLE + SEQUENCE for target_emb");
        }
        // 固定输出数值序列，实体域取两输入并集，保持逻辑推断与环境决策一致（C6/C10）。
        return OperatorSupport.fixedInference(inputs, DataType.DOUBLE, ValueShape.SEQUENCE);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        Object embeddings = arguments.get(0);
        Object target = arguments.get(1);
        int size = OperatorSupport.sequenceSize(embeddings, name(), "emb_seq");
        int dimensions = OperatorSupport.sequenceSize(target, name(), "target_emb");

        // 用 scale * sqrt(sumSquares) 表示模长，避免直接平方导致有限输入溢出或下溢。
        // 目标模长只累计一次；不物化归一化向量，也不在实例中保留请求状态。
        double targetScale = 0.0;
        double targetSquares = 0.0;
        for (int dimension = 0; dimension < dimensions; dimension++) {
            double value = finiteNumber(OperatorSupport.sequenceElementAt(
                    target, dimension, name(), "target_emb"), -1, dimension);
            double absolute = Math.abs(value);
            if (absolute > targetScale) {
                double ratio = targetScale / absolute;
                targetSquares = targetSquares * ratio * ratio + 1.0;
                targetScale = absolute;
            } else if (absolute != 0.0) {
                double ratio = absolute / targetScale;
                targetSquares += ratio * ratio;
            }
        }
        double targetNorm = Math.sqrt(targetSquares);
        List<Double> result = new ArrayList<Double>(size);
        for (int index = 0; index < size; index++) {
            List<?> embedding = OperatorSupport.asList(OperatorSupport.sequenceElementAt(
                    embeddings, index, name(), "emb_seq"), name(), "embedding at index " + index);
            if (embedding.size() != dimensions) {
                throw new IllegalArgumentException("cos_sim_seq dimension mismatch at embedding "
                        + index + ": expected " + dimensions + ", got " + embedding.size());
            }
            double scale = 0.0;
            double squares = 0.0;
            double dot = 0.0;
            int dimension = 0;
            for (Object element : embedding) {
                double value = finiteNumber(element, index, dimension);
                double absolute = Math.abs(value);
                if (absolute > scale) {
                    double ratio = scale / absolute;
                    squares *= ratio * ratio;
                    dot *= ratio;
                    scale = absolute;
                }
                if (absolute != 0.0) {
                    double scaled = value / scale;
                    squares += scaled * scaled;
                    if (targetScale != 0.0) {
                        double targetValue = ((Number) OperatorSupport.sequenceElementAt(
                                target, dimension, name(), "target_emb")).doubleValue();
                        dot += scaled * (targetValue / targetScale);
                    }
                }
                dimension++;
            }
            // 零向量仍完成全部元素校验；舍入误差仅在最后钳制到余弦定义域。
            double similarity = scale == 0.0 || targetScale == 0.0
                    ? 0.0 : dot / Math.sqrt(squares) / targetNorm;
            result.add(Math.max(-1.0, Math.min(1.0, similarity)));
        }
        return Collections.unmodifiableList(result);
    }

    private static double finiteNumber(Object value, int embeddingIndex, int dimension) {
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (Double.isFinite(number)) return number;
        }
        String location = embeddingIndex < 0 ? "target_emb" : "embedding " + embeddingIndex;
        throw new IllegalArgumentException("cos_sim_seq requires a finite numeric value at "
                + location + " dimension " + dimension);
    }
}
