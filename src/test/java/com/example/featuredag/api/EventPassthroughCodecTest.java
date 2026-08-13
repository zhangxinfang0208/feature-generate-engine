package com.example.featuredag.api;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.FeatureDefinition;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.runtime.SequenceBlock;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

/**
 * 事件输入边界契约（JUnit 4）：纯透传与深度不可变。
 * 输入只验证事件是 String key 的 Map，不改写字段名、不转换值类型；
 * SequenceBlock 对 Map/List 递归防御复制，输出不可修改且与调用方输入隔离。
 */
public final class EventPassthroughCodecTest {
    @Test
    public void passthroughKeepsFieldNamesValuesAndTypes() {
        FeatureInputDecoder decoder = newDecoder();
        Map<String, Object> decoded = decoder.decodeOffline(Map.of(
                "events", List.of(Map.of(
                        "item_id", "item-1",
                        "industry_id", "industry-1",
                        "timestamp", 1.5,
                        "event_type", "click",
                        "value", 2.0))));
        SequenceBlock events = (SequenceBlock) decoded.get("events");
        Map<String, Object> event = events.rowAtBaseIndex(0);
        assertEquals("item-1", event.get("item_id"));
        assertEquals("industry-1", event.get("industry_id"));
        assertEquals("click", event.get("event_type"));
        assertEquals(1.5, event.get("timestamp"));
        assertEquals(2.0, event.get("value"));
        assertFalse("字段名不得被改写为 camelCase", event.containsKey("itemId"));
        assertFalse(event.containsKey("industryId"));
        assertFalse(event.containsKey("eventType"));
    }

    @Test
    public void dualKeysAndUnknownAttributesRetained() {
        FeatureInputDecoder decoder = newDecoder();
        Map<String, Object> decoded = decoder.decodeOffline(Map.of(
                "events", List.of(Map.of(
                        "item_id", "snake-item",
                        "itemId", "camel-item",
                        "timestamp", 3L,
                        "tags", List.of("hot", "new"),
                        "score", 5))));
        SequenceBlock events = (SequenceBlock) decoded.get("events");
        Map<String, Object> event = events.rowAtBaseIndex(0);
        assertEquals("snake-item", event.get("item_id"));
        assertEquals("camel-item", event.get("itemId"));
        assertEquals(List.of("hot", "new"), event.get("tags"));
        assertEquals(5, event.get("score"));
    }

    @Test
    public void deepImmutabilityIsolatesEngineFromCallerMutation() {
        FeatureInputDecoder decoder = newDecoder();
        List<Object> tags = new ArrayList<>();
        tags.add("hot");
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("score", 9);
        Map<String, Object> mutableEvent = new LinkedHashMap<>();
        mutableEvent.put("item_id", "item-2");
        mutableEvent.put("tags", tags);
        mutableEvent.put("nested", nested);

        Map<String, Object> decoded = decoder.decodeOffline(
                Map.of("events", List.of(mutableEvent)));
        Map<String, Object> row =
                ((SequenceBlock) decoded.get("events")).rowAtBaseIndex(0);

        // 解码后修改原始事件（含嵌套 List/Map）不影响引擎内部表示。
        tags.add("changed");
        nested.put("score", 10);
        mutableEvent.put("item_id", "mutated");
        assertEquals("item-2", row.get("item_id"));
        assertEquals(List.of("hot"), row.get("tags"));
        assertEquals(Map.of("score", 9), row.get("nested"));

        // 输出事件 Map、嵌套 List、嵌套 Map 均不可修改。
        assertThrows(UnsupportedOperationException.class, () -> row.put("extra", 1));
        assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) row.get("tags")).add("x"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> ((Map<String, Object>) row.get("nested")).put("x", 1));
    }

    private static FeatureInputDecoder newDecoder() {
        List<FeatureDefinition> definitions = List.of(
                FeatureDefinition.builder()
                        .name("events")
                        .role(com.example.featuredag.definition.FeatureRole.RAW)
                        .dataType(DataType.EVENT_SEQUENCE)
                        .addEntityScope(EntityScope.USER)
                        .sourceBinding("events")
                        .declaredValueShape(ValueShape.SEQUENCE)
                        .build());
        LogicalDag dag = new LogicalDagBuilder(
                new ExpressionParser(), OperatorRegistry.standard())
                .build(definitions, Set.of("events"));
        return FeatureInputDecoder.from(dag);
    }
}
