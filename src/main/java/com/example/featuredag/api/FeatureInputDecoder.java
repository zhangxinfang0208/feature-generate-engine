package com.example.featuredag.api;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.SourceNode;
import com.example.featuredag.runtime.SequenceBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 输入解码器（对外契约适配）：把调用方提供的「外部 List 值」转换为内部源值——
 * SEQUENCE 形状保留完整 List，标量形状取首元素；
 * 在线模式按实体域拆分：非 ITEM 源值进共享输入，ITEM 源值逐候选解码；
 * 分组 Batch 保留相同拆分规则，并由执行上下文维护 group/candidate 边界。
 */
final class FeatureInputDecoder {
    private record SourceSpec(
            String sourceBinding,
            DataType dataType,
            ValueShape shape,
            boolean itemScoped) {}

    private final List<SourceSpec> sources;

    private FeatureInputDecoder(List<SourceSpec> sources) {
        this.sources = List.copyOf(sources);
    }

    static FeatureInputDecoder from(LogicalDag dag) {
        List<SourceSpec> sources = dag.orderedNodes().stream()
                .filter(SourceNode.class::isInstance)
                .map(SourceNode.class::cast)
                // 解码规则直接取自已验证的逻辑源节点，避免 API 层重新解释原始配置。
                .map(source -> new SourceSpec(
                        source.sourceBinding(),
                        source.outputType(),
                        source.valueShape(),
                        source.entityScopes().contains(EntityScope.ITEM)))
                .toList();
        return new FeatureInputDecoder(sources);
    }

    Map<String, Object> decodeOffline(Map<String, List<?>> external) {
        return decode(external, sources);
    }

    List<Map<String, Object>> decodeOfflineBatch(
            List<Map<String, List<?>>> externalRows) {
        return externalRows.stream().map(row -> decode(row, sources)).toList();
    }

    Map<String, Object> decodeOnlineShared(Map<String, List<?>> external) {
        return decode(external, sources.stream().filter(source -> !source.itemScoped()).toList());
    }

    List<Map<String, Object>> decodeOnlineSharedBatch(
            List<OnlineRequestGroup> groups) {
        return groups.stream()
                .map(group -> decodeOnlineShared(group.sharedValues()))
                .toList();
    }

    List<Map<String, Object>> decodeOnlineCandidates(
            List<Map<String, List<?>>> externalCandidates) {
        List<SourceSpec> itemSources = sources.stream().filter(SourceSpec::itemScoped).toList();
        return externalCandidates.stream().map(values -> decode(values, itemSources)).toList();
    }

    List<List<Map<String, Object>>> decodeOnlineCandidateBatch(
            List<OnlineRequestGroup> groups) {
        return groups.stream()
                .map(group -> decodeOnlineCandidates(group.candidates()))
                .toList();
    }

    private static Map<String, Object> decode(
            Map<String, List<?>> external,
            List<SourceSpec> sources) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (SourceSpec source : sources) {
            // 缺失值不在解码层报错：运行时 SOURCE_BINDING 还需要按实体域检查默认值和候选位置。
            if (!external.containsKey(source.sourceBinding())) continue;
            List<?> values = external.get(source.sourceBinding());
            Object decoded = decodeValue(source, values);
            result.put(source.sourceBinding(), decoded);
        }
        return result;
    }

    private static Object decodeValue(SourceSpec source, List<?> values) {
        if (values == null) {
            throw new IllegalArgumentException(
                    "Feature " + source.sourceBinding() + " values must not be null");
        }
        if (source.shape() == ValueShape.SEQUENCE) {
            // 普通序列只冻结外部 List；事件序列额外转换为支持视图和索引的列式运行时表示。
            if (source.dataType() == DataType.EVENT_SEQUENCE) {
                return decodeEventSequence(source.sourceBinding(), values);
            }
            return FeatureValueCollections.immutableList(values);
        }
        if (source.shape() == ValueShape.CANDIDATE_VECTOR) {
            // 向量保持全部元素；标量则遵循公共 API 的单元素 List 契约，只读取首项。
            return FeatureValueCollections.immutableList(values);
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException(
                    "Feature " + source.sourceBinding()
                            + " expects a non-empty List for " + source.shape());
        }
        return values.getFirst();
    }

    /**
     * 公共输入边界：EVENT_SEQUENCE 的普通 List 转为行式 SequenceBlock（事件 = 不可变 Map），
     * 使通用算子与序列索引融合共享同一种运行时表示（C1/C10）。
     * 纯透传契约：只验证每个事件是 String key 的 Map，不改写、不转换任何业务字段；
     * 深度防御复制与不可变化由 SequenceBlock 统一完成。
     */
    private static SequenceBlock decodeEventSequence(String sourceBinding, List<?> values) {
        List<Map<String, Object>> events = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            if (!(value instanceof Map<?, ?>)) {
                throw invalidEvent(
                        sourceBinding,
                        index,
                        "expected Map, got " + typeName(value));
            }
            Map<?, ?> event = (Map<?, ?>) value;
            for (Object key : event.keySet()) {
                if (!(key instanceof String)) {
                    throw invalidEvent(
                            sourceBinding,
                            index,
                            "event field names must be strings, got " + typeName(key));
                }
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> stringKeyed = (Map<String, Object>) event;
            events.add(stringKeyed);
        }
        String sequenceId = sourceBinding + "#"
                + Integer.toUnsignedString(System.identityHashCode(values));
        // sequenceId 表示本次外部序列实例，结合版本号参与视图/索引缓存隔离。
        return new SequenceBlock(sequenceId, 0L, events);
    }

    private static IllegalArgumentException invalidEvent(
            String sourceBinding,
            int index,
            String message) {
        return new IllegalArgumentException(
                "Invalid EVENT_SEQUENCE feature " + sourceBinding
                        + " at index " + index + ": " + message);
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }
}
