package com.example.featuredag.runtime;

import com.example.featuredag.api.*;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.physical.PhysicalPlan;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class OfflineIntermediateReleaseTest {
    @Test
    public void releasedAndRetainedExecutionHaveIdenticalOutputsAndRootValues() {
        AtomicReference<PhysicalPlan> captured = new AtomicReference<>();
        var engine = FeatureDagEngine.init(OfflineBorrowedInputTest.CONFIG, InitOptions.builder()
                .environment(com.example.featuredag.physical.ExecutionEnvironment.OFFLINE)
                .runtimeTraceObserver((id, plan, result) -> captured.set(plan)).build());
        engine.generateBatch(OfflineBatchGenerateRequest.borrowed("plan", List.of(OfflineBorrowedInputTest.row())));
        PhysicalPlan plan = captured.get();
        var retainedContext = ExecutionContext.offlineBatch("retain",
                List.of(Map.of("x", List.of(1, 2, 1), "t", 1, "b", 7L)));
        var releasedContext = ExecutionContext.offlineBatch("release",
                List.of(Map.of("x", List.of(1, 2, 1), "t", 1, "b", 7L)));
        var runtime = new DagRuntime(OperatorRegistry.standard());
        var retained = runtime.execute(plan, retainedContext);
        var released = runtime.execute(plan, releasedContext, false);
        ExternalValueMaterializer materializer = new ExternalValueMaterializer();
        for (String feature : plan.outputFeatureSlots().keySet()) {
            assertEquals(materializer.materialize(retained.feature(feature)), materializer.materialize(released.feature(feature)));
        }
        assertEquals(new java.util.HashSet<>(plan.outputFeatureSlots().values()), releasedContext.resultSlots().keySet());
        for (int i = 0; i < plan.nodes().size(); i++) {
            for (PhysicalPlan.SlotRelease release : plan.releasesAfterNode(i)) {
                assertNull(released.nodeStates().get(release.physicalNodeId()).resultHandle());
                assertNotNull(retained.nodeStates().get(release.physicalNodeId()).resultHandle());
            }
        }
    }
}
