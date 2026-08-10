package com.example.featuredag.api;

import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.SourceNode;
import com.example.featuredag.logical.ValueShape;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 输入解码器（对外契约适配）：把调用方提供的「外部 List 值」转换为内部源值——
 * SEQUENCE 形状保留完整 List，标量形状取首元素；
 * 在线模式按实体域拆分：非 ITEM 源值进共享输入，ITEM 源值逐候选解码。
 */
final class FeatureInputDecoder {
    private record SourceSpec(String sourceBinding, ValueShape shape, boolean itemScoped) {}

    private final List<SourceSpec> sources;

    private FeatureInputDecoder(List<SourceSpec> sources) {
        this.sources = List.copyOf(sources);
    }

    static FeatureInputDecoder from(LogicalDag dag) {
        List<SourceSpec> sources = dag.orderedNodes().stream()
                .filter(SourceNode.class::isInstance)
                .map(SourceNode.class::cast)
                .map(source -> new SourceSpec(
                        source.sourceBinding(),
                        source.valueShape(),
                        source.entityScopes().contains(EntityScope.ITEM)))
                .toList();
        return new FeatureInputDecoder(sources);
    }

    Map<String, Object> decodeOffline(Map<String, List<?>> external) {
        return decode(external, sources);
    }

    Map<String, Object> decodeOnlineShared(Map<String, List<?>> external) {
        return decode(external, sources.stream().filter(source -> !source.itemScoped()).toList());
    }

    List<Map<String, Object>> decodeOnlineCandidates(
            List<Map<String, List<?>>> externalCandidates) {
        List<SourceSpec> itemSources = sources.stream().filter(SourceSpec::itemScoped).toList();
        return externalCandidates.stream().map(values -> decode(values, itemSources)).toList();
    }

    private static Map<String, Object> decode(
            Map<String, List<?>> external,
            List<SourceSpec> sources) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (SourceSpec source : sources) {
            if (!external.containsKey(source.sourceBinding())) continue;
            List<?> values = external.get(source.sourceBinding());
            Object decoded = source.shape() == ValueShape.SEQUENCE
                    ? FeatureValueCollections.immutableList(values)
                    : values.getFirst();
            result.put(source.sourceBinding(), decoded);
        }
        return result;
    }
}
