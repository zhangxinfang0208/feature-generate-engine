package com.example.featuredag.runtime;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.GenerateResult;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OnlineGenerateRequest;
import com.example.featuredag.operator.OperatorResultList;
import com.example.featuredag.physical.ExecutionEnvironment;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

/**
 * 算子独占的不可变结果列表直接透传到输出，不再被值句柄和输出物化各复制一次。
 * 透传的前提是列表由引擎独占：借用调用方容器得到的视图、含容器元素的列表仍必须复制。
 */
public class OutputListPassThroughTest {
    private static final String CONFIG = """
            {"feature_set_name":"output-pass-through","version":"1","features":[
              {"name":"x","raw_name":"x","definition_type":"BASE","type":"INT","value_shape":"SEQUENCE","entity_scopes":["USER"]},
              {"name":"t","raw_name":"t","definition_type":"BASE","type":"INT","value_shape":"SCALAR","entity_scopes":["ITEM"]},
              {"name":"raw_x","definition_type":"DERIVED","type":"INT","value_shape":"SEQUENCE","expression":"x","output_policy":"OUTPUT"},
              {"name":"picked","definition_type":"DERIVED","type":"INT","value_shape":"SEQUENCE","expression":"slice_by_indices(x, find_indices(x, t))","output_policy":"OUTPUT"},
              {"name":"shifted","definition_type":"DERIVED","type":"BIGINT","value_shape":"SEQUENCE","expression":"sub(x, 0)","output_policy":"OUTPUT"}
            ]}
            """;

    private static final String EVENT_CONFIG = """
            {"feature_set_name":"output-pass-through-events","version":"1","features":[
              {"name":"events","raw_name":"events","definition_type":"BASE","type":"EVENT_SEQUENCE","value_shape":"SEQUENCE","entity_scopes":["USER"]},
              {"name":"t","raw_name":"t","definition_type":"BASE","type":"INT","value_shape":"SCALAR","entity_scopes":["ITEM"]},
              {"name":"raw_events","definition_type":"DERIVED","type":"EVENT_SEQUENCE","value_shape":"SEQUENCE","expression":"events","output_policy":"OUTPUT"}
            ]}
            """;

    private final ExternalValueMaterializer materializer = new ExternalValueMaterializer();

    @Test
    public void borrowedInputsNeverAliasTheReturnedLists() {
        FeatureDagEngine engine = engine(CONFIG);
        List<Integer> sequence = new ArrayList<>(List.of(1, 2, 1));
        List<Integer> target = new ArrayList<>(List.of(1));
        Map<String, List<?>> shared = new LinkedHashMap<>();
        shared.put("x", sequence);
        List<Map<String, List<?>>> candidates = List.of(Map.of("t", target));

        GenerateResult result = engine.generate(OnlineGenerateRequest.borrowed("borrowed", shared, candidates));

        Map<String, List<?>> sharedValues = result.featureValues();
        assertEquals(List.of(1, 2, 1), sharedValues.get("raw_x"));
        assertEquals(List.of(1L, 2L, 1L), sharedValues.get("shifted"));
        assertEquals(List.of(1, 1), result.candidateFeatureValues().get(0).get("picked"));
        // generate 返回后调用方复用输入容器，输出必须保持不变。
        sequence.clear();
        target.clear();
        shared.clear();
        assertEquals(List.of(1, 2, 1), sharedValues.get("raw_x"));
        assertEquals(List.of(1L, 2L, 1L), sharedValues.get("shifted"));
        assertEquals(List.of(1, 1), result.candidateFeatureValues().get(0).get("picked"));
        assertThrows(UnsupportedOperationException.class, sharedValues.get("raw_x")::clear);
        assertThrows(UnsupportedOperationException.class,
                () -> mutable(sharedValues.get("shifted")).set(0, List.of(9)));
        assertThrows(UnsupportedOperationException.class,
                () -> mutable(result.candidateFeatureValues().get(0).get("picked")).add(5));
    }

    @Test
    public void eventOutputsStayDetachedFromTheCallersEventMaps() {
        FeatureDagEngine engine = engine(EVENT_CONFIG);
        Map<String, Object> first = new LinkedHashMap<>(Map.of("id", "a"));
        Map<String, Object> second = new LinkedHashMap<>(Map.of("id", "b"));
        List<Map<String, Object>> events = new ArrayList<>(List.of(first, second));
        List<Integer> target = new ArrayList<>(List.of(1));
        Map<String, List<?>> shared = new LinkedHashMap<>();
        shared.put("events", events);
        Map<String, List<?>> item = new LinkedHashMap<>();
        item.put("t", target);

        GenerateResult result = engine.generate(
                OnlineGenerateRequest.borrowed("events", shared, List.of(item)));

        List<?> output = result.featureValues().get("raw_events");
        assertEquals(List.of(Map.of("id", "a"), Map.of("id", "b")), output);
        first.put("id", "changed");
        events.clear();
        assertEquals(List.of(Map.of("id", "a"), Map.of("id", "b")), output);
        assertThrows(UnsupportedOperationException.class, output::clear);
        assertThrows(UnsupportedOperationException.class, ((Map<String, Object>) output.get(0))::clear);
    }

    @Test
    public void snapshotRequestsProduceTheSameOutputsAsBorrowedOnes() {
        FeatureDagEngine engine = engine(CONFIG);
        Map<String, List<?>> shared = Map.of("x", new ArrayList<>(List.of(1, 2, 1)));
        List<Map<String, List<?>>> candidates = List.of(Map.of("t", new ArrayList<>(List.of(2))));

        GenerateResult snapshot = engine.generate(new OnlineGenerateRequest("snapshot", shared, candidates));
        GenerateResult borrowed = engine.generate(OnlineGenerateRequest.borrowed("borrowed", shared, candidates));

        assertEquals(snapshot.featureValues(), borrowed.featureValues());
        assertEquals(snapshot.candidateFeatureValues(), borrowed.candidateFeatureValues());
        assertEquals(List.of(2), snapshot.candidateFeatureValues().get(0).get("picked"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void materializerPassesThroughDetachedScalarListsOnly() {
        // null 也是合法的标量元素，但 List.of 不接受 null，这里用 Arrays.asList。
        List<Object> scalars = new ArrayList<>(Arrays.asList(1, "two", null));
        OperatorResultList<Object> operatorResult = OperatorResultList.wrap(scalars);
        List<Object> detachedFrozen = FrozenList.copyOf(scalars);
        List<Object> borrowedView = FrozenList.wrap(scalars);
        List<Object> withContainer = OperatorResultList.wrap(
                new ArrayList<>(List.of(1, new LinkedHashMap<>(Map.of("k", "v")))));
        List<Object> withHandle = OperatorResultList.wrap(
                new ArrayList<>(List.of(1, new ScalarValue(1))));

        assertSame(operatorResult, materializer.materializeRaw(operatorResult));
        assertSame(detachedFrozen, materializer.materializeRaw(detachedFrozen));
        // 借用视图、含容器或值句柄元素的列表仍复制并递归物化。
        assertNotSame(borrowedView, materializer.materializeRaw(borrowedView));
        assertEquals(scalars, materializer.materializeRaw(borrowedView));
        List<Object> copiedContainers = (List<Object>) materializer.materializeRaw(withContainer);
        assertNotSame(withContainer, copiedContainers);
        assertNotSame(((List<Object>) withContainer).get(1), copiedContainers.get(1));
        assertEquals(Map.of("k", "v"), copiedContainers.get(1));
        assertEquals(List.of(1, 1), materializer.materializeRaw(withHandle));
        // 需要截断时同样复制，且不会改动原列表。
        assertEquals(List.of(1), materializer.materializeRaw(operatorResult, 1));
        assertEquals(3, operatorResult.size());
    }

    @Test
    public void listSequenceValueReusesOperatorResultsWithoutCopying() {
        List<Object> scalars = new ArrayList<>(List.of(1, 2));
        OperatorResultList<Object> operatorResult = OperatorResultList.wrap(scalars);
        FrozenList<Long> frozen = FrozenList.wrap(List.of(1L, 2L));
        ListSequenceValue value = new ListSequenceValue("exec", operatorResult);
        ListSequenceValue fromFrozen = new ListSequenceValue("exec", frozen);

        assertSame(operatorResult, value.values());
        assertSame(frozen, fromFrozen.values());
        assertEquals(List.of(1, 2), value.values());
        assertEquals(List.of(1L, 2L), fromFrozen.values());
        // 普通来源仍保持拷贝语义。
        List<Object> mutable = new ArrayList<>(List.of(1, 2));
        ListSequenceValue copied = new ListSequenceValue("exec", mutable);
        mutable.set(0, 9);
        assertEquals(List.of(1, 2), copied.values());
    }

    @SuppressWarnings("unchecked")
    private static List<Object> mutable(List<?> list) {
        return (List<Object>) list;
    }

    private static FeatureDagEngine engine(String config) {
        return FeatureDagEngine.init(config, InitOptions.builder()
                .environment(ExecutionEnvironment.ONLINE).planId("output-pass-through-test").build());
    }
}
