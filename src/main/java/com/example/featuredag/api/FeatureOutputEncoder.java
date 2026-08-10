package com.example.featuredag.api;

import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.ValueShape;
import com.example.featuredag.runtime.ExternalValueMaterializer;
import com.example.featuredag.runtime.ValueHandle;

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
    private final Map<String, ValueShape> outputShapes;
    private final ExternalValueMaterializer materializer = new ExternalValueMaterializer();

    private FeatureOutputEncoder(Map<String, ValueShape> outputShapes) {
        this.outputShapes = Map.copyOf(outputShapes);
    }

    static FeatureOutputEncoder from(LogicalDag dag) {
        Map<String, ValueShape> shapes = new LinkedHashMap<>();
        dag.featureOutputNodeIds().keySet().forEach(featureName ->
                shapes.put(featureName, dag.featureOutput(featureName).valueShape()));
        return new FeatureOutputEncoder(shapes);
    }

    List<?> encode(String featureName, ValueHandle handle) {
        return encodeMaterialized(featureName, materializer.materialize(handle));
    }

    List<?> encodeCandidateElement(String featureName, Object value) {
        return encodeMaterialized(featureName, materializer.materializeRaw(value));
    }

    private List<?> encodeMaterialized(String featureName, Object value) {
        ValueShape shape = Objects.requireNonNull(
                outputShapes.get(featureName), "Unknown output feature: " + featureName);
        if (shape == ValueShape.SEQUENCE) {
            if (!(value instanceof List<?> list)) {
                throw new IllegalStateException(
                        "Sequence output did not materialize as List: " + featureName);
            }
            return FeatureValueCollections.immutableList(list);
        }
        return FeatureValueCollections.singleton(value);
    }
}
