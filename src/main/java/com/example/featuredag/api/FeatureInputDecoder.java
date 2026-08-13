package com.example.featuredag.api;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.SourceNode;
import com.example.featuredag.runtime.SequenceBlock;
import com.example.featuredag.runtime.SequenceEvent;

import java.math.BigDecimal;
import java.math.BigInteger;
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
            if (source.dataType() == DataType.EVENT_SEQUENCE) {
                return decodeEventSequence(source.sourceBinding(), values);
            }
            return FeatureValueCollections.immutableList(values);
        }
        if (source.shape() == ValueShape.CANDIDATE_VECTOR) {
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
     * 公共输入边界：EVENT_SEQUENCE 的普通 List 转为列式 SequenceBlock，
     * 使通用算子与序列索引融合共享同一种运行时表示（C1/C10）。
     */
    private static SequenceBlock decodeEventSequence(String sourceBinding, List<?> values) {
        List<SequenceEvent> events = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            Object value = values.get(index);
            if (value instanceof SequenceEvent event) {
                events.add(event);
            } else if (value instanceof Map<?, ?> event) {
                events.add(decodeEvent(sourceBinding, index, event));
            } else {
                throw invalidEvent(
                        sourceBinding,
                        index,
                        "expected SequenceEvent or Map, got " + typeName(value));
            }
        }
        String sequenceId = sourceBinding + "#"
                + Integer.toUnsignedString(System.identityHashCode(values));
        return new SequenceBlock(sequenceId, 0L, events);
    }

    private static SequenceEvent decodeEvent(
            String sourceBinding,
            int index,
            Map<?, ?> event) {
        return new SequenceEvent(
                stringField(event, "itemId", "item_id"),
                stringField(event, "industryId", "industry_id"),
                longField(sourceBinding, index, event, "timestamp"),
                stringField(event, "eventType", "event_type"),
                numberField(sourceBinding, index, event, "value").doubleValue());
    }

    /**
     * 时间戳必须为整数值：非整型或非有限浮点一律拒绝，
     * 避免 longValue() 把 1.5/NaN 静默截断为错误时间戳。
     */
    private static long longField(
            String sourceBinding,
            int index,
            Map<?, ?> event,
            String name) {
        Number value = numberField(sourceBinding, index, event, name);
        try {
            if (value instanceof BigDecimal decimal) return decimal.longValueExact();
            if (value instanceof BigInteger integer) return integer.longValueExact();
        } catch (ArithmeticException error) {
            throw invalidEvent(
                    sourceBinding, index, "field " + name + " is out of long range: " + value);
        }
        double doubleValue = value.doubleValue();
        long longValue = value.longValue();
        if (!Double.isFinite(doubleValue) || doubleValue != longValue) {
            throw invalidEvent(
                    sourceBinding, index,
                    "field " + name + " must be an integral number, got " + value);
        }
        return longValue;
    }

    private static String stringField(Map<?, ?> event, String name, String alias) {
        Object value = field(event, name, alias);
        return value == null ? null : String.valueOf(value);
    }

    private static Number numberField(
            String sourceBinding,
            int index,
            Map<?, ?> event,
            String name) {
        Object value = field(event, name, name);
        if (value instanceof Number number) return number;
        throw invalidEvent(
                sourceBinding,
                index,
                "field " + name + " must be numeric, got " + typeName(value));
    }

    private static Object field(Map<?, ?> event, String name, String alias) {
        if (event.containsKey(name)) return event.get(name);
        return event.get(alias);
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
