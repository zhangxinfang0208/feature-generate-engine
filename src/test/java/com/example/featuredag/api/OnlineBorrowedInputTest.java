package com.example.featuredag.api;

import com.example.featuredag.definition.*;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.physical.ExecutionEnvironment;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;

public class OnlineBorrowedInputTest {
    private static FeatureDagEngine engine(String config) {
        return FeatureDagEngine.init(config, InitOptions.builder()
                .environment(ExecutionEnvironment.ONLINE).planId("online-borrowed-test").build());
    }

    @Test
    public void snapshotIsolationBorrowedIdentityAndDetachedOutputs() {
        var engine = engine(OfflineBorrowedInputTest.CONFIG);
        List<Integer> sequence = new ArrayList<>(List.of(1, 2, 1));
        Map<String, List<?>> shared = new LinkedHashMap<>();
        shared.put("x", sequence);
        shared.put("b", List.of(7));
        List<Integer> target = new ArrayList<>(List.of(1));
        Map<String, List<?>> item = new LinkedHashMap<>();
        item.put("t", target);
        List<Map<String, List<?>>> items = new ArrayList<>(List.of(item));
        var snapshot = new OnlineGenerateRequest("snapshot", shared, items);
        var borrowed = OnlineGenerateRequest.borrowed("borrowed", shared, items);
        assertSame(sequence, borrowed.sharedValues().get("x"));
        assertSame(target, borrowed.candidates().getFirst().get("t"));
        GenerateResult actual = engine.generate(borrowed);
        GenerateResult expected = engine.generate(snapshot);
        assertEquals(expected.featureValues(), actual.featureValues());
        assertEquals(expected.candidateFeatureValues(), actual.candidateFeatureValues());
        assertEquals(List.of(1, 1), actual.candidateFeatureValues().getFirst().get("picked"));
        assertEquals(List.of(1, 2, 1), sequence);
        assertEquals(List.of(1), target);
        sequence.clear();
        target.clear();
        shared.clear();
        item.clear();
        items.clear();
        assertEquals(expected.featureValues(), engine.generate(snapshot).featureValues());
        assertEquals(expected.candidateFeatureValues(), engine.generate(snapshot).candidateFeatureValues());
        assertEquals(List.of(1, 1), actual.candidateFeatureValues().getFirst().get("picked"));
        assertThrows(UnsupportedOperationException.class, actual.featureValues()::clear);
        assertThrows(UnsupportedOperationException.class, actual.candidateFeatureValues()::clear);
        assertThrows(UnsupportedOperationException.class,
                actual.candidateFeatureValues().getFirst().get("picked")::clear);
        assertThrows(UnsupportedOperationException.class, borrowed.sharedValues()::clear);
        assertThrows(UnsupportedOperationException.class, borrowed.candidates()::clear);
    }

    @Test
    public void candidateOrderAndConcurrentReadOnlyRequestsRemainIsolated() throws Exception {
        var engine = engine(OfflineBorrowedInputTest.CONFIG);
        Map<String, List<?>> shared = Map.of("x", List.of(1, 2, 1), "b", List.of(7));
        var first = OnlineGenerateRequest.borrowed("first", shared,
                List.of(Map.of("t", List.of(1)), Map.of("t", List.of(2))));
        var second = OnlineGenerateRequest.borrowed("second", shared,
                List.of(Map.of("t", List.of(2)), Map.of("t", List.of(1))));
        var expectedFirst = engine.generate(first).candidateFeatureValues();
        var expectedSecond = engine.generate(second).candidateFeatureValues();
        assertEquals(List.of(1, 1), expectedFirst.get(0).get("picked"));
        assertEquals(List.of(2), expectedFirst.get(1).get("picked"));
        try (var pool = Executors.newFixedThreadPool(4)) {
            List<Callable<Void>> calls = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                boolean even = i % 2 == 0;
                calls.add(() -> {
                    assertEquals(even ? expectedFirst : expectedSecond,
                            engine.generate(even ? first : second).candidateFeatureValues());
                    return null;
                });
            }
            for (var future : pool.invokeAll(calls)) future.get();
        }
    }

    @Test
    public void borrowedBigintSequencesConvertExactlyAndRejectFractions() {
        var engine = engine(OfflineBorrowedInputTest.CONFIG.replace("\"type\":\"INT\"", "\"type\":\"BIGINT\""));
        for (List<?> sequence : List.of(List.of(1L, 2L, 1L), List.of(1L, 2, 1.0))) {
            var request = OnlineGenerateRequest.borrowed("bigint",
                    Map.of("x", sequence, "b", List.of(7)), List.of(Map.of("t", List.of(1))));
            assertEquals(List.of(1L, 1L), engine.generate(request).candidateFeatureValues().getFirst().get("picked"));
        }
        var invalid = OnlineGenerateRequest.borrowed("invalid",
                Map.of("x", List.of(1L, 2.5), "b", List.of(7)), List.of(Map.of("t", List.of(1))));
        assertThrows(FeatureGenerationException.class, () -> engine.generate(invalid));
    }

    @Test
    public void borrowedBoundaryStillValidatesNulls() {
        Map<String, List<?>> bad = new LinkedHashMap<>();
        bad.put("x", null);
        assertThrows(NullPointerException.class, () -> OnlineGenerateRequest.borrowed("id", bad, List.of()));
        assertThrows(NullPointerException.class, () -> OnlineGenerateRequest.borrowed("id", Map.of(), List.of(bad)));
        assertThrows(NullPointerException.class, () -> OnlineGenerateRequest.borrowed("id", Map.of(), null));
        assertThrows(IllegalArgumentException.class, () -> OnlineGenerateRequest.borrowed(" ", Map.of(), List.of()));
    }

    @Test
    public void readOnlyDecoderAvoidsCopyAndProtectsRuntimeView() {
        var shared = FeatureDefinition.builder().name("shared").role(FeatureRole.RAW)
                .dataType(DataType.INT).addEntityScope(EntityScope.USER).sourceBinding("shared")
                .declaredValueShape(ValueShape.SEQUENCE).build();
        var item = FeatureDefinition.builder().name("item").role(FeatureRole.RAW)
                .dataType(DataType.INT).addEntityScope(EntityScope.ITEM).sourceBinding("item")
                .declaredValueShape(ValueShape.SEQUENCE).build();
        var decoder = FeatureInputDecoder.from(new LogicalDagBuilder(new ExpressionParser(), OperatorRegistry.standard())
                .build(List.of(shared, item), Set.of("shared", "item")));
        List<Integer> input = new ArrayList<>(List.of(1, 2));
        List<?> sharedView = (List<?>) decoder.decodeOnlineSharedReadOnly(Map.of("shared", input)).get("shared");
        List<?> itemView = (List<?>) decoder.decodeOnlineCandidatesReadOnly(List.of(Map.of("item", input))).getFirst().get("item");
        assertThrows(UnsupportedOperationException.class, sharedView::clear);
        assertThrows(UnsupportedOperationException.class, itemView::clear);
        // 仅在解码器单测中改变底层列表，验证为视图；实际 generate 执行期间禁止修改。
        input.set(0, 9);
        assertEquals(9, sharedView.getFirst());
        assertEquals(9, itemView.getFirst());
    }
}
