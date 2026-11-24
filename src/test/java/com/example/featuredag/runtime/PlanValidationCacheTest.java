package com.example.featuredag.runtime;

import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.physical.CachePolicy;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.physical.ExecutionMode;
import com.example.featuredag.physical.ExecutionStage;
import com.example.featuredag.physical.ExecutorType;
import com.example.featuredag.physical.MaterializationPolicy;
import com.example.featuredag.physical.PhysicalNode;
import com.example.featuredag.physical.PhysicalPlan;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** 计划校验只依赖不可变计划与执行器注册表：同一计划不逐请求重复校验，注册表变化后重新校验。 */
public class PlanValidationCacheTest {

    @Test
    public void validatesAPlanOnceUntilTheExecutorRegistryChanges() {
        AtomicInteger validations = new AtomicInteger();
        PhysicalExecutorRegistry executors = new PhysicalExecutorRegistry()
                .register("counting", countingExecutor(validations));
        PhysicalPlan plan = specializedPlan(ExecutionEnvironment.ONLINE, ExecutionStage.CANDIDATE_BATCH);
        DagRuntime runtime = new DagRuntime(OperatorRegistry.standard(), executors);

        runtime.execute(plan, request("first"));
        runtime.execute(plan, request("second"));
        assertEquals(1, validations.get());

        executors.register("unrelated", (node, context, state) -> new ScalarValue(2));
        runtime.execute(plan, request("third"));
        assertEquals(2, validations.get());
    }

    @Test
    public void revalidatesWhenThePlanChanges() {
        AtomicInteger validations = new AtomicInteger();
        PhysicalExecutorRegistry executors = new PhysicalExecutorRegistry()
                .register("counting", countingExecutor(validations));
        PhysicalPlan first = specializedPlan(ExecutionEnvironment.ONLINE, ExecutionStage.CANDIDATE_BATCH);
        PhysicalPlan second = specializedPlan(ExecutionEnvironment.ONLINE, ExecutionStage.REQUEST_SHARED);
        DagRuntime runtime = new DagRuntime(OperatorRegistry.standard(), executors);

        runtime.execute(first, request("first"));
        runtime.execute(second, request("second"));
        assertEquals(2, validations.get());
    }

    @Test
    public void doesNotCacheAFailedValidation() {
        AtomicInteger validations = new AtomicInteger();
        PhysicalExecutorRegistry executors = new PhysicalExecutorRegistry()
                .register("counting", countingExecutor(validations));
        // 阶段校验在专用执行器校验之后执行：执行器校验先计数，再由阶段不一致报错。
        PhysicalPlan mismatched =
                specializedPlan(ExecutionEnvironment.OFFLINE, ExecutionStage.CANDIDATE_BATCH);
        DagRuntime runtime = new DagRuntime(OperatorRegistry.standard(), executors);

        assertThrows(IllegalArgumentException.class, () -> runtime.execute(mismatched, offlineRow()));
        assertThrows(IllegalArgumentException.class, () -> runtime.execute(mismatched, offlineRow()));
        assertEquals(2, validations.get());
    }

    private static PhysicalExecutor countingExecutor(AtomicInteger validations) {
        return new PhysicalExecutor() {
            @Override
            public void validate(PhysicalNode node) {
                validations.incrementAndGet();
            }

            @Override
            public ValueHandle execute(PhysicalNode node, ExecutionContext context, RuntimeNodeState state) {
                return new ScalarValue(1);
            }
        };
    }

    private static PhysicalPlan specializedPlan(ExecutionEnvironment environment, ExecutionStage stage) {
        PhysicalNode specialized = new PhysicalNode(
                "physical:counting",
                List.of("logical:counting"),
                ExecutorType.SPECIALIZED,
                "counting",
                stage,
                ExecutionMode.BATCH,
                ValueShape.SCALAR,
                List.of(),
                "slot:counting",
                CachePolicy.NONE,
                MaterializationPolicy.LAZY,
                Map.of());
        return new PhysicalPlan(
                "plan-validation-cache", environment, List.of(specialized), Map.of("counted", "slot:counting"));
    }

    private static ExecutionContext request(String executionId) {
        return ExecutionContext.onlineRequest(executionId, Map.of(), List.of(Map.of()));
    }

    private static ExecutionContext offlineRow() {
        return ExecutionContext.offlineRow("offline", Map.of());
    }
}
