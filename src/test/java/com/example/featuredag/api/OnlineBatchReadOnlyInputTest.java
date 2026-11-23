package com.example.featuredag.api;

import com.example.featuredag.physical.ExecutionEnvironment;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

/**
 * 在线分组批走只读解码与所有权交接：结果与单请求路径一致，
 * 借用分组直接引用调用方容器，输出仍与输入脱离且不可修改。
 */
public class OnlineBatchReadOnlyInputTest {

    private static FeatureDagEngine engine() {
        return FeatureDagEngine.init(OfflineBorrowedInputTest.CONFIG, InitOptions.builder()
                .environment(ExecutionEnvironment.ONLINE).planId("online-batch-readonly-test").build());
    }

    private static Map<String, List<?>> shared() {
        Map<String, List<?>> shared = new LinkedHashMap<>();
        shared.put("x", new ArrayList<>(List.of(1, 2, 1)));
        shared.put("b", new ArrayList<>(List.of(7)));
        return shared;
    }

    private static List<Map<String, List<?>>> candidates() {
        Map<String, List<?>> first = new LinkedHashMap<>();
        first.put("t", new ArrayList<>(List.of(1)));
        Map<String, List<?>> second = new LinkedHashMap<>();
        second.put("t", new ArrayList<>(List.of(2)));
        return new ArrayList<>(List.of(first, second));
    }

    @Test
    public void batchMatchesTheSingleRequestPathForTheSameInput() {
        FeatureDagEngine engine = engine();
        GenerateResult expected = engine.generate(new OnlineGenerateRequest("single", shared(), candidates()));
        OnlineBatchGenerateResult actual = engine.generateBatch(new OnlineBatchGenerateRequest(
                "batch", List.of(new OnlineRequestGroup("single", shared(), candidates()))));

        assertEquals(1, actual.groupResults().size());
        assertEquals(expected.featureValues(), actual.groupResults().get(0).featureValues());
        assertEquals(expected.candidateFeatureValues(), actual.groupResults().get(0).candidateFeatureValues());
        assertEquals(List.of(1, 1), actual.groupResults().get(0).candidateFeatureValues().get(0).get("picked"));
        assertEquals(List.of(2), actual.groupResults().get(0).candidateFeatureValues().get(1).get("picked"));
        assertEquals(List.of(2), actual.groupResults().get(0).featureValues().get("count"));
        assertEquals(List.of(8L), actual.groupResults().get(0).featureValues().get("big"));
    }

    @Test
    public void eachGroupKeepsItsOwnSharedInputsAndCandidateOrder() {
        Map<String, List<?>> firstShared = shared();
        Map<String, List<?>> secondShared = shared();
        secondShared.put("x", new ArrayList<>(List.of(3, 3, 3, 1)));
        List<Map<String, List<?>>> firstCandidates = List.of(candidates().get(0));
        List<Map<String, List<?>>> secondCandidates = List.of(candidates().get(1));

        OnlineBatchGenerateResult result = engine().generateBatch(new OnlineBatchGenerateRequest(
                "batch",
                List.of(
                        new OnlineRequestGroup("g1", firstShared, firstCandidates),
                        new OnlineRequestGroup("g2", secondShared, secondCandidates))));

        assertEquals(List.of("g1", "g2"),
                result.groupResults().stream().map(GenerateResult::executionId).toList());
        assertEquals(List.of(1, 1), result.groupResults().get(0).candidateFeatureValues().get(0).get("picked"));
        assertEquals(List.of(2), result.groupResults().get(0).featureValues().get("count"));
        // 第二组的序列里没有等于候选值的元素：picked 为空序列，而不是回落到第一组的结果。
        assertEquals(List.of(), result.groupResults().get(1).candidateFeatureValues().get(0).get("picked"));
        assertEquals(List.of(2), result.groupResults().get(1).featureValues().get("count"));
    }

    @Test
    public void borrowedGroupsAliasTheirInputsAndProduceDetachedOutputs() {
        Map<String, List<?>> shared = shared();
        List<Map<String, List<?>>> candidates = candidates();
        OnlineRequestGroup snapshot = new OnlineRequestGroup("g", shared, candidates);
        OnlineRequestGroup borrowed = OnlineRequestGroup.borrowed("g", shared, candidates);
        assertNotSame(shared.get("x"), snapshot.sharedValues().get("x"));
        assertSame(shared.get("x"), borrowed.sharedValues().get("x"));
        assertSame(candidates.get(0).get("t"), borrowed.candidates().get(0).get("t"));

        FeatureDagEngine engine = engine();
        GenerateResult expected = engine.generateBatch(
                new OnlineBatchGenerateRequest("snapshot", List.of(snapshot))).groupResults().get(0);
        GenerateResult actual = engine.generateBatch(
                new OnlineBatchGenerateRequest("borrowed", List.of(borrowed))).groupResults().get(0);
        assertEquals(expected.featureValues(), actual.featureValues());
        assertEquals(expected.candidateFeatureValues(), actual.candidateFeatureValues());

        // 输出与输入脱离：generateBatch 返回后调用方复用输入容器，不影响已返回的结果。
        assertEquals(List.of(1, 2, 1), shared.get("x"));
        shared.get("x").clear();
        candidates.get(0).get("t").clear();
        assertEquals(List.of(1, 1), actual.candidateFeatureValues().get(0).get("picked"));
        assertEquals(List.of(2), actual.featureValues().get("count"));
        assertEquals(List.of(8L), actual.featureValues().get("big"));
        assertThrows(UnsupportedOperationException.class, actual.featureValues()::clear);
        assertThrows(UnsupportedOperationException.class, actual.candidateFeatureValues()::clear);
        assertThrows(UnsupportedOperationException.class,
                actual.candidateFeatureValues().get(0).get("picked")::clear);
        assertThrows(UnsupportedOperationException.class, borrowed.sharedValues()::clear);
        assertThrows(UnsupportedOperationException.class, borrowed.candidates()::clear);
    }

    @Test
    public void borrowedBoundaryStillValidatesNulls() {
        Map<String, List<?>> nullValue = new LinkedHashMap<>();
        nullValue.put("x", null);
        Map<String, List<?>> nullCandidate = new LinkedHashMap<>();
        nullCandidate.put("t", null);
        assertThrows(NullPointerException.class,
                () -> OnlineRequestGroup.borrowed("id", nullValue, List.of()));
        assertThrows(NullPointerException.class,
                () -> OnlineRequestGroup.borrowed("id", Map.of(), List.of(nullCandidate)));
        assertThrows(NullPointerException.class,
                () -> OnlineRequestGroup.borrowed("id", Map.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> OnlineRequestGroup.borrowed(" ", Map.of(), List.of()));
    }
}
