package com.example.featuredag.runtime;

import com.example.featuredag.api.*;
import com.example.featuredag.definition.ValueShape;
import org.junit.Test;

import java.util.*;
import static org.junit.Assert.*;

public class RuntimeInputAndFailureCacheTest {
    @Test
    public void publicInputFactoryStillSnapshotsCallerMapsAndRows() {
        Map<String, Object> shared = new LinkedHashMap<>();
        shared.put("first", null);
        shared.put("second", 2);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("score", 3);
        List<Map<String, Object>> rows = new ArrayList<>(List.of(row));
        ExecutionContext context = ExecutionContext.onlineRequest("public", shared, rows);
        shared.clear(); row.clear(); rows.clear();
        assertEquals(Arrays.asList("first", "second"), new ArrayList<>(context.sharedSourceValues().keySet()));
        assertTrue(context.sharedSourceValues().containsKey("first"));
        assertNull(context.sharedSourceValues().get("first"));
        assertEquals(3, context.candidates().get(0).get("score"));
        assertSame(context.sharedSourceValues(), context.onlineSharedGroups().get(0));
        assertThrows(UnsupportedOperationException.class, context.sharedSourceValues()::clear);
        assertThrows(UnsupportedOperationException.class, context.candidates().get(0)::clear);
    }

    @Test
    public void decodedOwnershipTransferFreezesMapsAndSnapshotsOuterRows() {
        Map<String, Object> shared = new LinkedHashMap<>();
        shared.put("u", 1);
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("item", null);
        List<Map<String, Object>> rows = new ArrayList<>(List.of(candidate));
        ExecutionContext context = ExecutionContext.onlineRequestFromOwnedDecodedValues("owned", shared, rows);
        // 只修改允许继续持有的外层列表，不违反内部 Map 的所有权交接契约。
        rows.clear();
        assertEquals(1, context.candidateCount());
        assertTrue(context.candidates().get(0).containsKey("item"));
        assertSame(context.sharedSourceValues(), context.onlineSharedGroups().get(0));
        assertThrows(UnsupportedOperationException.class, context.candidates()::clear);
        assertThrows(UnsupportedOperationException.class,
                () -> context.candidates().get(0).entrySet().iterator().next().setValue(7));
        assertThrows(UnsupportedOperationException.class, () -> context.sharedSourceValues().put("u", 2));
        assertThrows(UnsupportedOperationException.class, context.onlineSharedGroups().get(0)::clear);
    }

    @Test
    public void failureMemoIsPerRequestAndDoesNotRescanImmutableColumns() {
        EvaluationFailure first = failure("first"), second = failure("second");
        CountingList source = new CountingList(Arrays.asList(null, first, second));
        OfflineBatchValue values = OfflineBatchValue.owned(source, ValueShape.SCALAR);
        ExecutionContext one = emptyContext("one");
        assertSame(first, one.firstBatchFailure(values));
        int reads = source.reads;
        assertSame(first, one.firstBatchFailure(values));
        assertEquals(reads, source.reads);
        assertSame(first, emptyContext("two").firstBatchFailure(values));
        assertTrue(source.reads > reads);
        CountingList healthy = new CountingList(Arrays.asList(1, null, 2));
        OfflineBatchValue ok = OfflineBatchValue.owned(healthy, ValueShape.SCALAR);
        assertNull(one.firstBatchFailure(ok));
        int healthyReads = healthy.reads;
        assertNull(one.firstBatchFailure(ok));
        assertEquals(healthyReads, healthy.reads);
    }

    @Test
    public void allBatchDomainsKeepTopLevelFailureSemantics() {
        ExecutionContext context = emptyContext("domains");
        EvaluationFailure error = failure("bad");
        List<Object> source = new ArrayList<>(Arrays.asList(null, error));
        List<ValueHandle> handles = List.of(new CandidateVectorValue(source),
                new OfflineBatchValue(source, ValueShape.SCALAR),
                new RequestBatchValue(source, ValueShape.SCALAR),
                new CandidateBatchValue(source, ValueShape.SCALAR));
        source.clear();
        for (ValueHandle value : handles) {
            assertSame(error, context.firstBatchFailure(value));
            assertSame(error, context.firstBatchFailure(value));
        }
        assertNull(context.firstBatchFailure(new CandidateVectorValue(List.of(List.of(error)))));
        assertNull(context.firstBatchFailure(new ScalarValue(List.of(error))));
        assertNull(context.firstBatchFailure(new CandidateVectorValue(List.of())));
    }

    @Test
    public void groupedBroadcastFailureSkipsEmptyGroupAndKeepsCandidateDefaults() {
        String config = """
                {"feature_set_name":"memo-test","version":"1","features":[
                  {"name":"u","raw_name":"u","type":"DOUBLE","definition_type":"BASE","entity_scopes":["USER"]},
                  {"name":"i","raw_name":"i","type":"DOUBLE","definition_type":"BASE","entity_scopes":["ITEM"]},
                  {"name":"out","type":"BIGINT","definition_type":"DERIVED",
                   "expression":"add(to_bigint(i), to_bigint(u))","dft":-1,"output_policy":"OUTPUT"}
                ]}
                """;
        FeatureDagEngine engine = FeatureDagEngine.init(config, InitOptions.online("memo-test"));
        List<OnlineRequestGroup> groups = List.of(
                new OnlineRequestGroup("empty", Map.of("u", List.of(Double.NaN)), List.of()),
                new OnlineRequestGroup("good", Map.of("u", List.of(5)), List.of(Map.of("i", List.of(2)))),
                new OnlineRequestGroup("bad", Map.of("u", List.of(Double.NaN)),
                        List.of(Map.of("i", List.of(3)), Map.of("i", List.of(4)))));
        OnlineBatchGenerateResult result = engine.generateBatch(new OnlineBatchGenerateRequest("batch", groups));
        assertTrue(result.groupResults().get(0).candidateFeatureValues().isEmpty());
        assertEquals(List.of(7L), result.groupResults().get(1).candidateFeatureValues().get(0).get("out"));
        assertEquals(List.of(-1L), result.groupResults().get(2).candidateFeatureValues().get(0).get("out"));
        assertEquals(List.of(-1L), result.groupResults().get(2).candidateFeatureValues().get(1).get("out"));
        GenerateResult next = engine.generate(new OnlineGenerateRequest("next", Map.of("u", List.of(10)),
                List.of(Map.of("i", List.of(2)))));
        assertEquals(List.of(12L), next.candidateFeatureValues().get(0).get("out"));
    }

    private static ExecutionContext emptyContext(String id) {
        return ExecutionContext.onlineRequest(id, Map.of(), List.of());
    }

    private static EvaluationFailure failure(String node) {
        return EvaluationFailure.batch(node, "candidate 1", new IllegalArgumentException(node));
    }

    private static final class CountingList extends AbstractList<Object> {
        private final List<Object> values;
        private int reads;
        private CountingList(List<Object> values) { this.values = values; }
        @Override public int size() { return values.size(); }
        @Override public Object get(int index) { reads++; return values.get(index); }
    }
}
