package com.example.featuredag.runtime;

import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.operator.SequenceKeyDomain;
import com.example.featuredag.operator.SequenceKeyDomains;
import com.example.featuredag.physical.CachePolicy;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.physical.ExecutionMode;
import com.example.featuredag.physical.ExecutionStage;
import com.example.featuredag.physical.ExecutorType;
import com.example.featuredag.physical.MaterializationPolicy;
import com.example.featuredag.physical.PhysicalExecutorIds;
import com.example.featuredag.physical.PhysicalNode;
import com.example.featuredag.physical.PhysicalPlan;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public final class SequenceKeyCountFailureRecoveryTest {
    @Test
    public void invalidCandidateKeyDefaultsOnlyThatCandidate() {
        SequenceIndexProvider provider = new FixedIndexProvider() {
            @Override
            public Object normalizeQueryKey(Object key) {
                if ("bad".equals(key)) throw new IllegalArgumentException("bad key");
                return key;
            }
        };
        ExecutionContext context = ExecutionContext.onlineRequest(
                "single-group", Map.of(), List.of(Map.of(), Map.of(), Map.of()));
        context.resultSlots().put("slot:sequence", sequence("sequence"));
        context.resultSlots().put(
                "slot:key", new CandidateVectorValue(List.of("a", "bad", "c")));

        ExecutionResult result = execute(context, provider);

        assertEquals(
                Arrays.asList(2, -1, 1),
                ((CandidateVectorValue) result.feature("result")).values());
        assertEquals(1, specializedState(result).operatorFailureCount());
        assertEquals(1, featureOutputState(result).fallbackCount());
    }

    @Test
    public void indexBuildFailureDefaultsOnlyItsOnlineRequestGroup() {
        SequenceIndexProvider provider = new FixedIndexProvider() {
            @Override
            public IndexValue build(SequenceValue sequence) {
                if ("group-1".equals(sequence.baseBlock().sequenceId())) {
                    throw new IllegalStateException("index build failed");
                }
                return super.build(sequence);
            }
        };
        ExecutionContext context = ExecutionContext.onlineBatch(
                "two-groups",
                List.of("request-0", "request-1"),
                List.of(Map.of(), Map.of()),
                List.of(
                        List.of(Map.of(), Map.of()),
                        List.of(Map.of(), Map.of())));
        context.resultSlots().put(
                "slot:sequence",
                new RequestBatchValue(
                        List.of(sequence("group-0"), sequence("group-1")),
                        ValueShape.SEQUENCE));
        context.resultSlots().put(
                "slot:key",
                new CandidateBatchValue(
                        List.of("a", "c", "a", "c"),
                        ValueShape.SCALAR));

        ExecutionResult result = execute(context, provider);

        assertEquals(
                Arrays.asList(2, 1, -1, -1),
                ((CandidateBatchValue) result.feature("result")).values());
        assertEquals(2, featureOutputState(result).fallbackCount());
    }

    @Test
    public void inheritedSequenceFailureSkipsProviderAndDefaultsEveryCandidate() {
        AtomicInteger buildCount = new AtomicInteger();
        SequenceIndexProvider provider = new FixedIndexProvider() {
            @Override
            public IndexValue build(SequenceValue sequence) {
                buildCount.incrementAndGet();
                return super.build(sequence);
            }
        };
        ExecutionContext context = ExecutionContext.onlineRequest(
                "inherited", Map.of(), List.of(Map.of(), Map.of(), Map.of()));
        context.resultSlots().put(
                "slot:sequence",
                new FailedValueHandle(
                        ValueShape.SEQUENCE,
                        EvaluationFailure.single(
                                "physical:upstream",
                                new IllegalArgumentException("upstream failed"))));
        context.resultSlots().put(
                "slot:key", new CandidateVectorValue(List.of("a", "c", "a")));

        ExecutionResult result = execute(context, provider);

        assertEquals(
                Arrays.asList(-1, -1, -1),
                ((CandidateVectorValue) result.feature("result")).values());
        assertEquals(0, buildCount.get());
        assertEquals(3, featureOutputState(result).fallbackCount());
    }

    private static ExecutionResult execute(
            ExecutionContext context,
            SequenceIndexProvider provider) {
        SequenceIndexRegistry indexes = new SequenceIndexRegistry().register(provider);
        PhysicalPlan plan = plan();
        return new DagRuntime(
                new OperatorRegistry(),
                PhysicalExecutorRegistry.standard(indexes))
                .execute(plan, context);
    }

    private static PhysicalPlan plan() {
        PhysicalNode specialized = new PhysicalNode(
                "physical:specialized",
                List.of("operator:filter", "operator:count"),
                ExecutorType.SPECIALIZED,
                PhysicalExecutorIds.SEQUENCE_KEY_COUNT,
                ExecutionStage.CANDIDATE_BATCH,
                ExecutionMode.CANDIDATE_KEY,
                ValueShape.SCALAR,
                List.of("slot:sequence", "slot:key"),
                "slot:count",
                CachePolicy.CANDIDATE_KEY,
                MaterializationPolicy.LAZY,
                Map.of("keyDomain", SequenceKeyDomains.INDUSTRY.value()));
        PhysicalNode output = new PhysicalNode(
                "physical:output",
                List.of("feature:result"),
                ExecutorType.FEATURE_OUTPUT,
                PhysicalExecutorIds.FEATURE_OUTPUT,
                ExecutionStage.CANDIDATE_BATCH,
                ExecutionMode.BATCH,
                ValueShape.SCALAR,
                List.of("slot:count"),
                "slot:output",
                CachePolicy.NONE,
                MaterializationPolicy.LAZY,
                Map.of(
                        "featureName", "result",
                        "isRoot", true,
                        "defaultValue", -1,
                        "widenIntegralToBigint", false,
                        "widenIntegralToDouble", false));
        return new PhysicalPlan(
                "specialized-fallback",
                ExecutionEnvironment.ONLINE,
                List.of(specialized, output),
                Map.of("result", "slot:output"));
    }

    private static RuntimeNodeState specializedState(ExecutionResult result) {
        return result.nodeStates().get("physical:specialized");
    }

    private static RuntimeNodeState featureOutputState(ExecutionResult result) {
        return result.nodeStates().get("physical:output");
    }

    private static SequenceBlock sequence(String id) {
        return new SequenceBlock(
                id,
                1L,
                List.of(
                        Map.of("industryId", "a"),
                        Map.of("industryId", "a"),
                        Map.of("industryId", "c")));
    }

    private static class FixedIndexProvider implements SequenceIndexProvider {
        @Override
        public SequenceKeyDomain keyDomain() {
            return SequenceKeyDomains.INDUSTRY;
        }

        @Override
        public SequenceKeyExtractor keyExtractor() {
            return (block, index) -> block.columnValueAt("industryId", index);
        }
    }
}
