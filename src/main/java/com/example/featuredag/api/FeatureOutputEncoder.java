package com.example.featuredag.api;

import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.config.FeatureOutputDescriptor;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.runtime.ExternalValueMaterializer;
import com.example.featuredag.runtime.ValueHandle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 输出编码器（对外契约适配）：把内部 ValueHandle 物化为外部 List——
 * SEQUENCE 输出原样返回元素 List，其余形状包装为单元素 List；
 * 候选向量输出的每个候选元素单独编码。
 */
final class FeatureOutputEncoder {
    private record OutputSpec(
            ValueShape shape,
            Object defaultValue,
            Integer sequenceMaxLength) {}

    private final Map<String, OutputSpec> outputSpecs;
    private final ExternalValueMaterializer materializer = new ExternalValueMaterializer();

    private FeatureOutputEncoder(Map<String, OutputSpec> outputSpecs) {
        this.outputSpecs = Map.copyOf(outputSpecs);
    }

    static FeatureOutputEncoder from(LogicalDag dag) {
        return from(dag, List.of());
    }

    static FeatureOutputEncoder from(
            LogicalDag dag,
            List<FeatureOutputDescriptor> outputs) {
        Map<String, Integer> sequenceMaxLengths = new LinkedHashMap<>();
        for (FeatureOutputDescriptor output : outputs) {
            sequenceMaxLengths.put(output.featureName(), output.sequenceMaxLength());
        }
        Map<String, OutputSpec> specs = new LinkedHashMap<>();
        // 输出形状取自逻辑推断最终结果，而非原始配置声明，确保编码契约与实际节点一致（C6）。
        dag.featureOutputNodeIds().keySet().forEach(featureName -> {
            ValueShape shape = dag.featureOutput(featureName).valueShape();
            Integer sequenceMaxLength = sequenceMaxLengths.get(featureName);
            if (sequenceMaxLength != null
                    && sequenceMaxLength > 1
                    && shape != ValueShape.SEQUENCE) {
                throw new IllegalArgumentException(
                        "seq_max_length for non-sequence feature " + featureName
                                + " must be 1, got: " + sequenceMaxLength);
            }
            specs.put(
                    featureName,
                    new OutputSpec(
                            shape,
                            dag.featureOutput(featureName).defaultValue(),
                            sequenceMaxLength));
        });
        return new FeatureOutputEncoder(specs);
    }

    List<?> encode(String featureName, ValueHandle handle) {
        return encodeValue(featureName, Objects.requireNonNull(handle, "handle"));
    }

    List<?> encodeCandidateElement(String featureName, Object value) {
        return encodeBatchElement(featureName, value);
    }

    List<?> encodeBatchElement(String featureName, Object value) {
        return encodeValue(featureName, value);
    }

    private List<?> encodeValue(String featureName, Object rawValue) {
        OutputSpec spec = Objects.requireNonNull(
                outputSpecs.get(featureName), "Unknown output feature: " + featureName);
        // C6：长度规范化只发生于最终输出；物化器不遍历将被丢弃的序列后缀。
        Object value = spec.shape() == ValueShape.SEQUENCE && spec.sequenceMaxLength() != null
                ? materializer.materializeRaw(rawValue, spec.sequenceMaxLength())
                : materializer.materializeRaw(rawValue);
        // 公共 API 始终返回 List：序列展开为元素列表，标量/对象包装为单元素列表。
        if (spec.shape() == ValueShape.SEQUENCE) {
            if (!(value instanceof List<?> list)) {
                throw new IllegalStateException(
                        "Sequence output did not materialize as List: " + featureName);
            }
            return normalizeSequence(list, spec);
        }
        return FeatureValueCollections.singleton(value);
    }

    /** seq_max_length 是最终模型输入形状：超长截断，不足按 dft（含 null）补齐。 */
    private static List<?> normalizeSequence(List<?> values, OutputSpec spec) {
        Integer sequenceMaxLength = spec.sequenceMaxLength();
        // values 已由物化器构造并冻结，不需要再次进行防御复制。
        if (sequenceMaxLength == null || values.size() == sequenceMaxLength) return values;
        int targetLength = sequenceMaxLength;
        List<Object> normalized = new ArrayList<>(targetLength);
        int copied = Math.min(values.size(), targetLength);
        for (int index = 0; index < copied; index++) {
            normalized.add(values.get(index));
        }
        while (normalized.size() < targetLength) {
            normalized.add(spec.defaultValue());
        }
        return Collections.unmodifiableList(normalized);
    }
}
