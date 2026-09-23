package com.example.featuredag.runtime;

import com.example.featuredag.definition.*;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.operator.*;
import com.example.featuredag.physical.*;
import com.example.featuredag.physical.rewrite.PhysicalRewriteRegistry;
import com.example.featuredag.planning.LogicalDagOptimizer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/** Batch 连续行和投影行布局必须保持相同的业务位置、错误恢复与结果隔离。 */
public class BatchFastPathRuntimeTest {
    @Test
    public void healthyRowsKeepLocationsInEveryBatchDomain() {
        Probe probe = new Probe(false, false);
        assertEquals(List.of("-1:0:1", "-1:1:2"), values(run(probe, EntityScope.USER,
                ExecutionContext.offlineBatch("offline", List.of(score(1), score(2))))));
        assertEquals(List.of("0:0:1", "0:1:2"), values(run(probe, EntityScope.ITEM,
                ExecutionContext.onlineRequest("single", Map.of(), List.of(score(1), score(2))))));
        assertEquals(List.of("0:0:1", "1:0:2"), values(run(probe, EntityScope.USER,
                ExecutionContext.onlineBatch("requests", List.of("a", "b"),
                        List.of(score(1), score(2)), List.of(List.of(), List.of())))));
        assertEquals(List.of("0:0:1", "0:1:2", "2:0:3"), values(run(probe, EntityScope.ITEM,
                grouped(List.of(score(1), score(2)), List.of(), List.of(score(3))))));

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (int i = 0; i < 300; i++) candidates.add(score(i));
        List<?> results = values(run(probe, EntityScope.ITEM,
                ExecutionContext.onlineRequest("large", Map.of(), candidates)));
        assertEquals(300, results.size());
        assertEquals("0:299:299", results.get(299));
    }

    @Test
    public void inheritedAndNewFailuresKeepOriginalGroupAndCandidateIndexes() {
        Probe probe = new Probe(false, false);
        probe.failAtThirteen = true;
        ExecutionResult result = run(probe, EntityScope.ITEM, grouped(
                List.of(score(2.5e9), score(1)), List.of(),
                List.of(score(13), score(2.5e9), score(3), score(2.5e9))));
        assertEquals(List.of("fallback", "0:1:1", "fallback", "fallback", "2:2:3", "fallback"),
                values(result));
        assertEquals(List.of("0:1:1", "2:0:13", "2:2:3"), probe.locations);

        // 首次失败较晚时，快速路径之前已经扫描的健康行也必须进入投影。
        Probe lateFailure = new Probe(false, false);
        assertEquals(List.of("0:0:1", "0:1:2", "fallback"), values(run(lateFailure, EntityScope.ITEM,
                ExecutionContext.onlineRequest("late", Map.of(),
                        List.of(score(1), score(2), score(2.5e9))))));
    }

    @Test
    public void kernelFailureOnContinuousRowsAndEmptyOrAllFailedBatches() {
        Probe probe = new Probe(false, false);
        probe.failAtThirteen = true;
        assertEquals(List.of("0:0:1", "fallback", "0:2:3"), values(run(probe, EntityScope.ITEM,
                ExecutionContext.onlineRequest("kernel-failure", Map.of(),
                        List.of(score(1), score(13), score(3))))));
        int invocations = probe.calls;
        assertEquals(List.of("fallback", "fallback"), values(run(probe, EntityScope.ITEM,
                ExecutionContext.onlineRequest("all-failed", Map.of(),
                        List.of(score(2.5e9), score(2.5e9))))));
        assertTrue(values(run(probe, EntityScope.ITEM,
                ExecutionContext.onlineRequest("empty", Map.of(), List.of()))).isEmpty());
        assertEquals(invocations, probe.calls);
    }

    @Test
    public void customMutableResultColumnsAreSnapshotted() {
        Probe probe = new Probe(true, false);
        List<?> first = values(run(probe, EntityScope.USER,
                ExecutionContext.offlineBatch("first", List.of(score(1), score(2)))));
        probe.lastValues.set(0, "changed");
        assertEquals(List.of("-1:0:1", "-1:1:2"), first);
        assertThrows(UnsupportedOperationException.class, first::clear);
        run(probe, EntityScope.USER,
                ExecutionContext.offlineBatch("second", List.of(score(9))));
        assertEquals(List.of("-1:0:1", "-1:1:2"), first);
    }

    @Test
    public void materializationSharesWithinGroupAndIsolatesGroupsAndCalls() {
        SequenceView view = SequenceView.slice(new SequenceBlock("events", 1,
                List.of(Map.of("id", 0), Map.of("id", 1), Map.of("id", 2))), 1, 3);
        for (boolean direct : List.of(false, true)) {
            Probe probe = new Probe(false, direct);
            ExecutionContext context = ExecutionContext.onlineBatch("views", List.of("a", "b"),
                    List.of(Map.of("events", view), Map.of("events", view)),
                    List.of(List.of(score(1), score(2)), List.of(score(3))));
            runSequence(probe, context);
            assertEquals(3, probe.sequences.size());
            Object first = probe.sequences.get(0);
            assertSame(first, probe.sequences.get(1));
            if (direct) {
                assertSame(view, first);
                assertSame(view, probe.sequences.get(2));
            } else {
                assertEquals(List.of(Map.of("id", 1), Map.of("id", 2)), first);
                assertNotSame(first, probe.sequences.get(2));
                assertThrows(UnsupportedOperationException.class, ((List<?>) first)::clear);
                runSequence(probe, ExecutionContext.onlineBatch("next", List.of("c"),
                        List.of(Map.of("events", view)), List.of(List.of(score(4)))));
                assertNotSame(first, probe.sequences.get(3));
            }
        }
    }

    private static ExecutionContext grouped(List<Map<String, Object>> a,
            List<Map<String, Object>> b, List<Map<String, Object>> c) {
        return ExecutionContext.onlineBatch("groups", List.of("a", "b", "c"),
                List.of(Map.of(), Map.of(), Map.of()), List.of(a, b, c));
    }

    private static Map<String, Object> score(double value) { return Map.of("score", value); }

    private static List<?> values(ExecutionResult result) { return (List<?>) result.feature("result").raw(); }

    private static ExecutionResult run(Probe probe, EntityScope scope, ExecutionContext context) {
        return execute(probe, context, List.of(
                FeatureDefinition.raw("score", DataType.DOUBLE, scope, null),
                FeatureDefinition.builder().name("result").role(FeatureRole.DERIVED)
                        .dataType(DataType.STRING).expressionContent("batch_probe(to_int(score))")
                        .defaultValue("fallback").outputPolicy(OutputPolicy.OUTPUT).build()));
    }

    private static void runSequence(Probe probe, ExecutionContext context) {
        execute(probe, context, List.of(
                FeatureDefinition.raw("events", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                FeatureDefinition.raw("score", DataType.DOUBLE, EntityScope.ITEM, null),
                FeatureDefinition.derived("result", DataType.STRING,
                        "batch_probe(events, events, score)", OutputPolicy.OUTPUT)));
    }

    private static ExecutionResult execute(Probe probe, ExecutionContext context,
            List<FeatureDefinition> definitions) {
        OperatorRegistry registry = OperatorRegistry.standard().register(probe);
        var dag = new LogicalDagBuilder(new ExpressionParser(), registry).build(definitions, Set.of("result"));
        var plan = new PhysicalPlanner(registry, new PhysicalRewriteRegistry()).plan(
                new LogicalDagOptimizer(registry).analyze(dag), context.environment(), "fast-path-test");
        return new DagRuntime(registry).execute(plan, context);
    }

    private static final class Probe implements OperatorDefinition, RecoverableBatchOperatorKernel {
        private final boolean mutableColumn;
        private final boolean direct;
        private boolean failAtThirteen;
        private int calls;
        private List<Object> lastValues;
        private final List<String> locations = new ArrayList<>();
        private final List<Object> sequences = new ArrayList<>();

        private Probe(boolean mutableColumn, boolean direct) {
            this.mutableColumn = mutableColumn;
            this.direct = direct;
        }

        @Override public String name() { return "batch_probe"; }
        @Override public int minArguments() { return 1; }
        @Override public int maxArguments() { return 3; }
        @Override public boolean deterministic() { return false; }
        @Override public boolean supportsSequenceView() { return direct; }
        @Override public OperatorInference infer(List<OperatorInputMetadata> inputs) {
            Set<EntityScope> scopes = new java.util.LinkedHashSet<>();
            inputs.forEach(input -> scopes.addAll(input.entityScopes()));
            return new OperatorInference(DataType.STRING, scopes, ValueShape.SCALAR);
        }
        @Override public Object evaluate(List<Object> arguments) { throw new AssertionError("Expected Batch"); }

        @Override public BatchOperatorResult evaluateBatch(BatchOperatorCall call) {
            calls++;
            assertThrows(IndexOutOfBoundsException.class, () -> call.layout().groupIndexAt(-1));
            assertThrows(IndexOutOfBoundsException.class, () -> call.layout().indexInGroupAt(call.rowCount()));
            BatchOperatorResultBuilder builder = new BatchOperatorResultBuilder(call.rowCount());
            lastValues = new ArrayList<>();
            for (int row = 0; row < call.rowCount(); row++) {
                int number = ((Number) call.arguments().get(call.arguments().size() - 1).valueAt(row)).intValue();
                String location = call.layout().groupIndexAt(row) + ":" + call.layout().indexInGroupAt(row) + ":" + number;
                locations.add(location);
                if (call.arguments().size() == 3) {
                    Object sequence = call.arguments().get(0).valueAt(row);
                    assertSame(sequence, call.arguments().get(1).valueAt(row));
                    sequences.add(sequence);
                }
                if (failAtThirteen && number == 13) builder.addFailure(new IllegalArgumentException("bad thirteen"));
                else builder.addValue(location);
                lastValues.add(location);
            }
            if (!mutableColumn) return builder.build();
            List<Object> captured = lastValues;
            return new BatchOperatorResult(new BatchColumn() {
                @Override public int size() { return captured.size(); }
                @Override public Object valueAt(int row) { return captured.get(row); }
            });
        }
    }
}
