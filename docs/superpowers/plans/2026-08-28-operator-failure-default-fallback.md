# Operator Failure Default Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Propagate every operator-kernel `RuntimeException` as an internal per-evaluation failure and apply each derived feature's non-null `dft` at its `FEATURE_OUTPUT` boundary without aborting healthy Batch rows.

**Architecture:** Add success/failure outcomes at the operator protocol, carry failures through runtime-only handles and Batch elements, short-circuit downstream evaluation units, and resolve failures only at feature boundaries. Planning records whether a logical node can reach a feature with `dft`, selects only recovery-capable Native Batch/fusion implementations for those paths, and otherwise uses the Single semantic baseline.

**Tech Stack:** Java 21, JDK 8-compatible standard builtin operator sources, Maven, JUnit 4.13.2.

**Spec:** `docs/superpowers/specs/2026-08-27-operator-failure-default-fallback-design.md`

## Global Constraints

- The repository build baseline is Java 21.
- Every standard builtin operator and directly shared `operator.builtin` support source must use only JDK 1.8 language features and standard-library APIs.
- Do not modify `src/test/java/com/example/featuredag/DagEngineSelfTest.java`.
- Every new unit test must be an independent JUnit 4 `*Test.java` file under `src/test/java`.
- Catch only `RuntimeException` thrown inside operator-kernel evaluation; never catch `Error`.
- Keep parsing, inference, registration, arity, source binding, decoding, plan invariants, Batch protocol invariants, caching type checks, and encoding fail-fast.
- Apply `dft` only in `FEATURE_OUTPUT`; failure values must never contain a default value.
- Preserve row count, group boundaries, candidate order, and healthy values in every Batch domain.
- Do not retry failed operators and do not add business-operator-name branches in planning, physical, or runtime layers.
- Keep direct `OperatorRegistry.evaluate` and `evaluateBatch` calls fail-fast.
- Do not modify or stage unrelated dirty-worktree files, including the existing untracked `docs/guides` tree.

## File Structure

**Operator outcome protocol**

- Create `src/main/java/com/example/featuredag/operator/OperatorEvaluationResult.java`: immutable Single success/failure outcome.
- Create `src/main/java/com/example/featuredag/operator/RecoverableBatchOperatorKernel.java`: marker for Native kernels that report every row failure without aborting.
- Create `src/main/java/com/example/featuredag/operator/BatchOperatorResultBuilder.java`: JDK 8-compatible sequential row collector used by standard builtins and the scalar adapter.
- Modify `BatchOperatorResult.java`, `SingleLoopBatchOperatorKernel.java`, and `OperatorRegistry.java`: row failures plus recovering APIs while preserving fail-fast public APIs.

**Planning and physical capability selection**

- Modify `NodePlanningMetadata.java` and `LogicalDagOptimizer.java`: add `failureRecoveryRequired` computed by reverse propagation from every non-null derived `dft` boundary.
- Modify `PhysicalPlanner.java`: select a recovery-capable Native kernel or Scalar Adapter from metadata and registered capability.
- Modify `PhysicalRewrite.java`, `PhysicalRewriteRegistry.java`, and `CountAfterKeyedSequenceFilterRule.java`: declare and enforce specialized recovery capability.

**Runtime failure propagation**

- Create `src/main/java/com/example/featuredag/runtime/EvaluationFailure.java`: runtime-only cause and location.
- Create `src/main/java/com/example/featuredag/runtime/FailedValueHandle.java`: Single failure handle retaining logical shape.
- Create `src/main/java/com/example/featuredag/runtime/FeatureEvaluationException.java`: feature-boundary exception when no `dft` exists.
- Modify `ValueHandle.java` and `DagRuntime.java`: Single propagation, projected healthy Batch calls, scatter, and feature-boundary resolution.
- Modify `SequenceKeyCountExecutor.java`: per-group/per-candidate failure output for the standard specialized path.

**Diagnostics and public API**

- Modify `RuntimeNodeState.java`, `NodeExecutionSnapshot.java`, and `FeatureDagEngine.java`: failure/fallback counters and public feature-name mapping without exposing `Throwable` in observation snapshots.

**Tests and documentation**

- Create focused JUnit 4 protocol, planning, runtime Single, runtime Batch, and specialized-executor tests.
- Update the existing JUnit 4 assertion that freezes the old “calculation exception is not masked” behavior.
- Update tracked README and architecture documents; do not stage the user's untracked `docs/guides` content.

---

### Task 1: Add Recovering Operator Result Protocol

**Files:**
- Create: `src/main/java/com/example/featuredag/operator/OperatorEvaluationResult.java`
- Create: `src/main/java/com/example/featuredag/operator/RecoverableBatchOperatorKernel.java`
- Create: `src/main/java/com/example/featuredag/operator/BatchOperatorResultBuilder.java`
- Modify: `src/main/java/com/example/featuredag/operator/BatchOperatorResult.java`
- Modify: `src/main/java/com/example/featuredag/operator/SingleLoopBatchOperatorKernel.java`
- Modify: `src/main/java/com/example/featuredag/operator/OperatorRegistry.java`
- Test: `src/test/java/com/example/featuredag/operator/OperatorFailureRecoveryProtocolTest.java`

**Interfaces:**
- Produces: `OperatorEvaluationResult.success(Object)`, `failure(RuntimeException)`, `failed()`, `value()`, `failure()`.
- Produces: `BatchOperatorResult(BatchColumn, Map<Integer, RuntimeException>)`, `rowFailures()`, `hasFailures()`.
- Produces: `BatchOperatorResultBuilder(int expectedRows)`, `addValue(Object)`, `addFailure(RuntimeException)`, `build()`.
- Produces: `OperatorRegistry.evaluateRecovering(String, List<Object>)` and `evaluateBatchRecovering(String, BatchOperatorCall, BatchKernelKind)`.
- Preserves: existing `evaluate` and `evaluateBatch` throw the first original failure; Batch throws `BatchOperatorEvaluationException` at the smallest failed row.

- [ ] **Step 1: Write the failing protocol tests**

```java
@Test
public void recoveringSingleCapturesKernelRuntimeExceptionButDirectCallStillThrows() {
    OperatorRegistry registry = new OperatorRegistry().register(new ConditionalFailOperator());

    OperatorEvaluationResult result = registry.evaluateRecovering(
            "conditional_fail", List.<Object>of("bad"));

    assertTrue(result.failed());
    assertEquals("bad value", result.failure().getMessage());
    assertThrows(IllegalStateException.class,
            () -> registry.evaluate("conditional_fail", List.<Object>of("bad")));
}

@Test
public void recoveringScalarBatchKeepsHealthyRowsAndRecordsFailedRow() {
    OperatorRegistry registry = new OperatorRegistry().register(new ConditionalFailOperator());
    BatchOperatorCall call = new BatchOperatorCall(
            new FixedLayout(BatchDomain.OFFLINE_ROW, 3),
            List.<BatchColumn>of(new ListBatchColumn(List.<Object>of("a", "bad", "c"))));

    BatchOperatorResult result = registry.evaluateBatchRecovering(
            "conditional_fail", call, BatchKernelKind.SCALAR_ADAPTER);

    assertEquals("ok:a", result.values().valueAt(0));
    assertNull(result.values().valueAt(1));
    assertEquals("ok:c", result.values().valueAt(2));
    assertEquals("bad value", result.rowFailures().get(1).getMessage());

    BatchOperatorEvaluationException direct = assertThrows(
            BatchOperatorEvaluationException.class,
            () -> registry.evaluateBatch(
                    "conditional_fail", call, BatchKernelKind.SCALAR_ADAPTER));
    assertEquals(1, direct.rowIndex());
}
```

Use these complete test helpers:

```java
private static final class ConditionalFailOperator implements OperatorDefinition {
    @Override public String name() { return "conditional_fail"; }
    @Override public int minArguments() { return 1; }
    @Override public int maxArguments() { return 1; }
    @Override public boolean deterministic() { return true; }
    @Override public boolean supportsSequenceView() { return false; }
    @Override public boolean sideEffectFree() { return true; }
    @Override public List<OperatorSemantic> semantics() { return Collections.emptyList(); }
    @Override public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        return new OperatorInference(
                DataType.STRING, inputs.get(0).entityScopes(), ValueShape.SCALAR);
    }
    @Override public Object evaluate(List<Object> arguments) {
        Object value = arguments.get(0);
        if ("bad".equals(value)) throw new IllegalStateException("bad value");
        return "ok:" + value;
    }
}

private record FixedLayout(BatchDomain domain, int rowCount) implements BatchLayout {
    @Override public int groupIndexAt(int rowIndex) { return 0; }
    @Override public int indexInGroupAt(int rowIndex) { return rowIndex; }
}
```

- [ ] **Step 2: Run the protocol test and verify RED**

Run:

```bash
mvn -Dtest=OperatorFailureRecoveryProtocolTest test
```

Expected: compilation fails because `OperatorEvaluationResult`, recovering registry methods, and Batch row failures do not exist.

- [ ] **Step 3: Implement immutable Single and Batch outcomes**

```java
public final class OperatorEvaluationResult {
    private final Object value;
    private final RuntimeException failure;

    private OperatorEvaluationResult(Object value, RuntimeException failure) {
        this.value = value;
        this.failure = failure;
    }

    public static OperatorEvaluationResult success(Object value) {
        return new OperatorEvaluationResult(value, null);
    }

    public static OperatorEvaluationResult failure(RuntimeException failure) {
        return new OperatorEvaluationResult(null, Objects.requireNonNull(failure, "failure"));
    }

    public boolean failed() { return failure != null; }
    public Object value() { return value; }
    public RuntimeException failure() { return failure; }
}
```

Change `BatchOperatorResult` to copy an ordered failure map, reject null causes and out-of-range indexes, and preserve `new BatchOperatorResult(values)` as an empty-failure convenience constructor. `BatchOperatorResultBuilder.addFailure` appends a `null` value and records the current index; `build()` returns a result whose value-column size equals the number of calls to `addValue` plus `addFailure`.

- [ ] **Step 4: Add recovering Registry calls and keep public calls fail-fast**

```java
public OperatorEvaluationResult evaluateRecovering(String name, List<Object> arguments) {
    OperatorDefinition definition = require(name);
    validateArity(definition, arguments.size());
    try {
        return OperatorEvaluationResult.success(definition.evaluate(arguments));
    } catch (RuntimeException error) {
        return OperatorEvaluationResult.failure(error);
    }
}

public Object evaluate(String name, List<Object> arguments) {
    OperatorEvaluationResult result = evaluateRecovering(name, arguments);
    if (result.failed()) throw result.failure();
    return result.value();
}
```

`evaluateBatchRecovering` performs lookup, arity and planned-kind validation, calls the chosen kernel, validates total result rows, and returns failures without throwing. Existing `evaluateBatch` delegates to it and throws the smallest `rowFailures` entry as `BatchOperatorEvaluationException`.

- [ ] **Step 5: Make Scalar Adapter collect every row**

Replace the result list in `SingleLoopBatchOperatorKernel` with `BatchOperatorResultBuilder`. For each row, call `addValue(singleKernel.evaluate(arguments))`; catch only `RuntimeException` and call `addFailure(error)`. Declare the adapter as `implements RecoverableBatchOperatorKernel`.

- [ ] **Step 6: Run protocol tests and the existing operator suites**

Run:

```bash
mvn -Dtest=OperatorFailureRecoveryProtocolTest,NumericCastAndExtremeOperatorsTest,ArithmeticOperatorsTest test
```

Expected: all tests pass; existing direct Batch tests still receive `BatchOperatorEvaluationException` with the same row index.

- [ ] **Step 7: Commit the protocol**

```bash
git add src/main/java/com/example/featuredag/operator src/test/java/com/example/featuredag/operator/OperatorFailureRecoveryProtocolTest.java
git commit -m "Add recoverable operator evaluation results"
```

### Task 2: Make Standard Native Batch Kernels Row-Recoverable

**Files:**
- Modify: `src/main/java/com/example/featuredag/operator/builtin/FindIndicesOperator.java`
- Modify: `src/main/java/com/example/featuredag/operator/builtin/CountDistinctOperator.java`
- Modify: `src/main/java/com/example/featuredag/operator/builtin/ZipConcatOperator.java`
- Modify: `src/main/java/com/example/featuredag/operator/builtin/CalculateDeltaSequenceOperator.java`
- Modify: `src/main/java/com/example/featuredag/operator/OperatorRegistry.java`
- Test: `src/test/java/com/example/featuredag/operator/NativeBatchFailureRecoveryTest.java`

**Interfaces:**
- Consumes: `BatchOperatorResultBuilder` and `RecoverableBatchOperatorKernel` from Task 1.
- Produces: `OperatorRegistry.recoveringBatchKernelKind(String)` returning `NATIVE` only for a registered Native kernel that implements `RecoverableBatchOperatorKernel`; otherwise `SCALAR_ADAPTER`.

- [ ] **Step 1: Write failing Native recovery tests**

```java
@Test
public void standardNativeKernelsDeclareRowRecovery() {
    OperatorRegistry registry = OperatorRegistry.standard();
    for (String name : Arrays.asList(
            "find_indices", "count_distinct", "zip_concat", "calc_delta_seq")) {
        assertEquals(BatchKernelKind.NATIVE, registry.recoveringBatchKernelKind(name));
    }
}

@Test
public void nativeDeltaRecordsOnlyInvalidRow() {
    OperatorRegistry registry = OperatorRegistry.standard();
    BatchOperatorCall call = new BatchOperatorCall(
            new FixedLayout(BatchDomain.OFFLINE_ROW, 3),
            Arrays.<BatchColumn>asList(
                    new ListBatchColumn(Arrays.<Object>asList(
                            Arrays.asList(1.0), Arrays.asList(2.0), Arrays.asList(3.0))),
                    new ListBatchColumn(Arrays.<Object>asList(5.0, Double.NaN, 7.0))));

    BatchOperatorResult result = registry.evaluateBatchRecovering(
            "calc_delta_seq", call, BatchKernelKind.NATIVE);

    assertEquals(Arrays.asList(4.0), result.values().valueAt(0));
    assertNull(result.values().valueAt(1));
    assertEquals(Arrays.asList(4.0), result.values().valueAt(2));
    assertEquals(1, result.rowFailures().size());
    assertTrue(result.rowFailures().containsKey(1));
}
```

Add `LegacyNativeOperator implements OperatorDefinition, BatchOperatorKernel` without the recovery marker and assert `batchKernelKind("legacy_native") == NATIVE` while `recoveringBatchKernelKind("legacy_native") == SCALAR_ADAPTER`.

- [ ] **Step 2: Run the Native test and verify RED**

Run:

```bash
mvn -Dtest=NativeBatchFailureRecoveryTest test
```

Expected: compilation fails because `recoveringBatchKernelKind` is absent and standard Native kernels do not return row failures.

- [ ] **Step 3: Convert all four Native kernels**

For every standard Native kernel, keep its existing row body and replace only result collection and the catch action. For example, `CalculateDeltaSequenceOperator.evaluateBatch` becomes:

```java
public final class CalculateDeltaSequenceOperator extends AbstractBuiltinOperator
        implements BatchOperatorKernel, RecoverableBatchOperatorKernel {
    public BatchOperatorResult evaluateBatch(BatchOperatorCall call) {
        BatchOperatorResultBuilder result = new BatchOperatorResultBuilder(call.rowCount());
        Map<DeltaBatchKey, Object> values = new LinkedHashMap<DeltaBatchKey, Object>();
        for (int rowIndex = 0; rowIndex < call.rowCount(); rowIndex++) {
            try {
                Object sequence = call.arguments().get(0).valueAt(rowIndex);
                double base = OperatorSupport.finiteDouble(
                        call.arguments().get(1).valueAt(rowIndex), "calc_delta_seq base");
                DeltaOptions options = call.arguments().size() == 3
                        ? DeltaOptions.from(call.arguments().get(2).valueAt(rowIndex))
                        : DeltaOptions.defaults();
                DeltaBatchKey key = new DeltaBatchKey(
                        call.layout().groupIndexAt(rowIndex), sequence, base, options);
                Object value = values.get(key);
                if (value == null) {
                    value = calculateWithBase(sequence, base, options);
                    values.put(key, value);
                }
                result.addValue(value);
            } catch (RuntimeException error) {
                result.addFailure(error);
            }
        }
        return result.build();
    }
}
```

Keep the existing per-operator identity caches and existing JDK 8-compatible syntax. Do not move business logic into the builder or registry.

- [ ] **Step 4: Implement recovering capability lookup**

```java
public BatchKernelKind recoveringBatchKernelKind(String name) {
    require(name);
    BatchOperatorKernel registered = batchKernel(name);
    return registered.batchKernelKind() == BatchKernelKind.NATIVE
            && registered instanceof RecoverableBatchOperatorKernel
            ? BatchKernelKind.NATIVE
            : BatchKernelKind.SCALAR_ADAPTER;
}
```

- [ ] **Step 5: Run Native and standard registration tests**

Run:

```bash
mvn -Dtest=NativeBatchFailureRecoveryTest,HwdspClick365dAllOperatorsTest test
```

Expected: all tests pass; the four existing native registrations remain Native on healthy data.

- [ ] **Step 6: Commit Native recovery**

```bash
git add src/main/java/com/example/featuredag/operator src/main/java/com/example/featuredag/operator/builtin src/test/java/com/example/featuredag/operator/NativeBatchFailureRecoveryTest.java
git commit -m "Make native batch failures row recoverable"
```

### Task 3: Plan Recovery-Capable Kernels and Rewrites

**Files:**
- Modify: `src/main/java/com/example/featuredag/planning/NodePlanningMetadata.java`
- Modify: `src/main/java/com/example/featuredag/planning/LogicalDagOptimizer.java`
- Modify: `src/main/java/com/example/featuredag/physical/PhysicalPlanner.java`
- Modify: `src/main/java/com/example/featuredag/physical/rewrite/PhysicalRewrite.java`
- Modify: `src/main/java/com/example/featuredag/physical/rewrite/PhysicalRewriteRegistry.java`
- Modify: `src/main/java/com/example/featuredag/physical/rewrite/CountAfterKeyedSequenceFilterRule.java`
- Test: `src/test/java/com/example/featuredag/planning/FailureRecoveryPlanningTest.java`
- Test: `src/test/java/com/example/featuredag/physical/rewrite/CountAfterKeyedSequenceFilterRuleTest.java`

**Interfaces:**
- Consumes: `OperatorRegistry.recoveringBatchKernelKind` from Task 2.
- Produces: `NodePlanningMetadata.failureRecoveryRequired()`.
- Produces: `PhysicalRewrite.failureRecoverySupported()`.

- [ ] **Step 1: Write failing planning metadata tests**

Build three DAGs and assert:

```java
assertTrue(recoveredPlan.metadata().node(
        recoveredDag.featureOutput("result").producerNodeId())
        .failureRecoveryRequired());
assertFalse(failFastPlan.metadata().node(
        failFastDag.featureOutput("result").producerNodeId())
        .failureRecoveryRequired());
assertTrue(sharedPlan.metadata().node(sharedProducerId)
        .failureRecoveryRequired());
```

Use `FeatureDefinition.builder()` to create `to_int(score)` with `defaultValue(-1)` for the recovered DAG, omit the default for the fail-fast DAG, and create two derived features with the same expression but different default presence for the shared DAG.

Add a synthetic legacy Native operator and assert the physical operator config contains:

```java
assertEquals(
        BatchKernelKind.SCALAR_ADAPTER.name(),
        recoveredPhysicalNode.executorConfig().get("batchKernelKind"));
assertEquals(
        BatchKernelKind.NATIVE.name(),
        failFastPhysicalNode.executorConfig().get("batchKernelKind"));
```

- [ ] **Step 2: Run planning tests and verify RED**

Run:

```bash
mvn -Dtest=FailureRecoveryPlanningTest,CountAfterKeyedSequenceFilterRuleTest test
```

Expected: compilation fails because metadata and rewrite recovery flags do not exist.

- [ ] **Step 3: Compute recovery reachability without mutating logical nodes**

Add `boolean failureRecoveryRequired` to `NodePlanningMetadata`. In `LogicalDagOptimizer`, create a map initialized to false, walk topological order in reverse, seed every `FeatureOutputNode` whose `defaultValue() != null`, and propagate the current true value to every input node:

```java
private static Map<String, Boolean> computeFailureRecoveryRequired(LogicalDag dag) {
    Map<String, Boolean> required = new LinkedHashMap<>();
    for (String nodeId : dag.nodes().keySet()) required.put(nodeId, false);
    List<String> order = dag.topologicalOrder();
    for (int index = order.size() - 1; index >= 0; index--) {
        String nodeId = order.get(index);
        LogicalNode node = dag.node(nodeId);
        boolean current = required.get(nodeId)
                || node instanceof FeatureOutputNode output
                        && output.defaultValue() != null;
        required.put(nodeId, current);
        if (current) {
            for (NodeInput input : node.inputs()) required.put(input.nodeId(), true);
        }
    }
    return required;
}
```

- [ ] **Step 4: Select the planned Batch kind from metadata**

In `PhysicalPlanner.createGenericPhysicalNode`, use `recoveringBatchKernelKind` only when `metadata.failureRecoveryRequired()` is true; otherwise keep `batchKernelKind`.

- [ ] **Step 5: Gate physical rewrites by recovery capability**

Add `boolean failureRecoverySupported` to `PhysicalRewrite`. In `PhysicalRewriteRegistry.select`, discard a candidate when any consumed logical node has `failureRecoveryRequired()` and the rewrite flag is false. Keep the standard count-after-filter rewrite flag false in this task; Task 6 changes it to true in the same commit that adds executor recovery.

Extend `CountAfterKeyedSequenceFilterRuleTest` with a synthetic rewrite whose flag is false and assert it is excluded only when its consumed node reaches a non-null `dft` boundary.

- [ ] **Step 6: Run planning and rewrite tests**

Run:

```bash
mvn -Dtest=FailureRecoveryPlanningTest,CountAfterKeyedSequenceFilterRuleTest test
```

Expected: all tests pass and no logical node is modified during analysis.

- [ ] **Step 7: Commit planning capability selection**

```bash
git add src/main/java/com/example/featuredag/planning src/main/java/com/example/featuredag/physical src/test/java/com/example/featuredag/planning/FailureRecoveryPlanningTest.java src/test/java/com/example/featuredag/physical/rewrite/CountAfterKeyedSequenceFilterRuleTest.java
git commit -m "Plan failure recovery capabilities"
```

### Task 4: Propagate Single Failures to Feature Defaults

**Files:**
- Create: `src/main/java/com/example/featuredag/runtime/EvaluationFailure.java`
- Create: `src/main/java/com/example/featuredag/runtime/FailedValueHandle.java`
- Create: `src/main/java/com/example/featuredag/runtime/FeatureEvaluationException.java`
- Modify: `src/main/java/com/example/featuredag/runtime/ValueHandle.java`
- Modify: `src/main/java/com/example/featuredag/runtime/DagRuntime.java`
- Modify: `src/main/java/com/example/featuredag/runtime/RuntimeNodeState.java`
- Modify: `src/test/java/com/example/featuredag/api/DerivedFeatureDefaultValueTest.java`
- Test: `src/test/java/com/example/featuredag/runtime/DerivedFeatureOperatorFallbackRuntimeTest.java`

**Interfaces:**
- Consumes: `OperatorRegistry.evaluateRecovering` from Task 1.
- Produces: `EvaluationFailure.single(String, RuntimeException)` and `batch(String, String, RuntimeException)` with `cause()`, `physicalNodeId()`, and `location()`.
- Produces: `FailedValueHandle(ValueShape, EvaluationFailure)`.
- Produces: `FeatureEvaluationException.featureName()` and original operator cause chain.
- Produces: `RuntimeNodeState.operatorFailureCount()` and `fallbackCount()`.

- [ ] **Step 1: Write failing Single runtime tests**

```java
@Test
public void nestedFailureUsesWholeFeatureDefaultAndSkipsDownstream() {
    ExecutionResult result = executeSingle(
            definitions(
                    rawDouble("score"),
                    derivedInt("result", "add(to_int(score), 10)", -1)),
            Map.of("score", 2.5e9),
            Set.of("result"));

    assertEquals(Integer.valueOf(-1), result.feature("result").raw());
    assertTrue(featureOutputState(result, "result").fallbackUsed());
    assertEquals(1, featureOutputState(result, "result").fallbackCount());
}

@Test
public void intermediateDefaultBecomesNormalDownstreamInput() {
    ExecutionResult result = executeSingle(
            definitions(
                    rawDouble("score"),
                    derivedInt("safe_score", "to_int(score)", 0, OutputPolicy.INTERNAL_ONLY),
                    derivedBigint("result", "add(safe_score, 10)", -1L)),
            Map.of("score", 2.5e9),
            Set.of("result"));

    assertEquals(Long.valueOf(10L), result.feature("result").raw());
}

@Test
public void sharedFailureUsesEachFeatureDefault() {
    ExecutionResult result = executeSingle(
            definitions(
                    rawDouble("score"),
                    derivedInt("left", "to_int(score)", -1),
                    derivedInt("right", "to_int(score)", 999)),
            Map.of("score", 2.5e9),
            Set.of("left", "right"));

    assertEquals(Integer.valueOf(-1), result.feature("left").raw());
    assertEquals(Integer.valueOf(999), result.feature("right").raw());
}
```

Also assert a feature without `dft` throws `FeatureEvaluationException` whose cause chain contains the original `IllegalArgumentException`, and a custom operator throwing `AssertionError` propagates that `Error` unchanged.

- [ ] **Step 2: Run Single runtime test and verify RED**

Run:

```bash
mvn -Dtest=DerivedFeatureOperatorFallbackRuntimeTest test
```

Expected: the first operator exception aborts `DagRuntime` instead of returning a default.

- [ ] **Step 3: Implement runtime-only failure types**

`EvaluationFailure` is package-private and immutable. `FailedValueHandle` is public only because the sealed `ValueHandle` permits list requires a concrete type; its `raw()` returns the `EvaluationFailure`, but public encoders must never receive it. Add it to `ValueHandle.permits`.

`EvaluationFailure.toString()` must return the constant `"<operator-failure>"` so opt-in runtime trace cannot expose the original exception message.

Add non-negative `operatorFailureCount` and `fallbackCount` fields to `RuntimeNodeState`, expose package/public getters used by runtime diagnostics, and copy both fields in `snapshot()`.

```java
public final class FailedValueHandle implements ValueHandle {
    private final ValueShape shape;
    private final EvaluationFailure failure;

    FailedValueHandle(ValueShape shape, EvaluationFailure failure) {
        this.shape = Objects.requireNonNull(shape, "shape");
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    EvaluationFailure failure() { return failure; }
    @Override public ValueShape shape() { return shape; }
    @Override public Object raw() { return failure; }
}
```

- [ ] **Step 4: Short-circuit Single inputs and capture only Kernel exceptions**

In `applySingleOrBatchByInputDomain`, before materializing arguments, return a new `FailedValueHandle(logicalValueShape, firstInputFailure)` when any input handle is failed. Otherwise call `evaluateRecovering`; convert a failed outcome to `EvaluationFailure` and increment the current operator state's own failure count.

- [ ] **Step 5: Resolve failure only in FEATURE_OUTPUT**

Pass `featureName` into default application. A failed Single handle with non-null `defaultValue` becomes `defaultHandle`; without a default, throw:

```java
throw new FeatureEvaluationException(
        featureName,
        failure.location(),
        failure.cause());
```

Keep ordinary `null` and empty-value handling unchanged, apply default before numeric widening, set `fallbackUsed`, and increment `fallbackCount` by one.

Rename `DerivedFeatureDefaultValueTest.calculationExceptionIsNotMaskedByDerivedDefault` to `calculationExceptionUsesDerivedDefault` and replace its `assertThrows` with:

```java
GenerateResult result = engine.generate(new OfflineGenerateRequest(
        "error-case", Map.of("score", List.of(0.0))));
assertEquals(List.of(99.0), result.featureValues().get("score_log"));
```

- [ ] **Step 6: Run Single runtime and existing default tests**

Run:

```bash
mvn -Dtest=DerivedFeatureOperatorFallbackRuntimeTest,DerivedFeatureDefaultValueTest,FeatureOutputDoubleWideningTest test
```

Expected: all tests pass; ordinary null/empty defaults and numeric widening remain unchanged.

- [ ] **Step 7: Commit Single runtime propagation**

```bash
git add src/main/java/com/example/featuredag/runtime src/test/java/com/example/featuredag/runtime/DerivedFeatureOperatorFallbackRuntimeTest.java src/test/java/com/example/featuredag/api/DerivedFeatureDefaultValueTest.java
git commit -m "Apply feature defaults to single operator failures"
```

### Task 5: Isolate Batch Failures and Preserve Healthy Rows

**Files:**
- Modify: `src/main/java/com/example/featuredag/runtime/DagRuntime.java`
- Test: `src/test/java/com/example/featuredag/runtime/OperatorBatchFailureFallbackRuntimeTest.java`

**Interfaces:**
- Consumes: Batch row failures from Task 1 and `EvaluationFailure` from Task 4.
- Produces: projected `RuntimeBatchLayout` mapping local healthy rows to original evaluation rows.
- Produces: original-order merged lists containing successful values or runtime-only failures.

- [ ] **Step 1: Write failing Offline and Online Batch tests**

```java
@Test
public void offlineBatchReplacesOnlyOverflowRow() {
    ExecutionResult result = executeOfflineBatch(
            definitions(
                    rawDouble("score"),
                    derivedInt("result", "add(to_int(score), 10)", -1)),
            List.of(
                    Map.of("score", 12.8),
                    Map.of("score", 2.5e9),
                    Map.of("score", 3.6)),
            Set.of("result"));

    OfflineBatchValue values = (OfflineBatchValue) result.feature("result");
    assertEquals(Arrays.asList(22, -1, 13), values.values());
}

@Test
public void onlineCandidatesReplaceOnlyInvalidCandidate() {
    ExecutionResult result = executeOnline(
            itemScoreDefinitionsWithDefault(-1),
            List.of(
                    Map.of("score", 12.8),
                    Map.of("score", 2.5e9),
                    Map.of("score", 3.6)),
            Set.of("result"));

    CandidateVectorValue values = (CandidateVectorValue) result.feature("result");
    assertEquals(Arrays.asList(22, -1, 13), values.values());
}
```

Add an Online Grouped Batch case with two groups and one invalid candidate in the first group. Assert only that flattened candidate uses `dft`, and add a custom counting downstream operator to prove it executes twice, not three times. Add a Native `calc_delta_seq` Batch with one NaN base and assert only that row defaults.

- [ ] **Step 2: Run Batch runtime test and verify RED**

Run:

```bash
mvn -Dtest=OperatorBatchFailureFallbackRuntimeTest test
```

Expected: the first failed row still aborts the entire Batch.

- [ ] **Step 3: Project healthy rows before Kernel invocation**

Change `RuntimeBatchLayout` to accept an immutable original-row index list. `originalRowIndex(localRow)` maps through that list; `groupIndexAt` and `indexInGroupAt` continue using the mapped original row.

Before creating `BatchOperatorCall`, scan original rows in order. Use `argumentAt` on every input handle; if an argument is `EvaluationFailure`, store the first failure for that original row and do not include the row in `healthyRows`.

- [ ] **Step 4: Evaluate healthy rows and scatter outcomes**

Call `operatorRegistry.evaluateBatchRecovering` only when `healthyRows` is non-empty. Allocate an original-size list initialized with inherited failures. For each local result row:

```java
int originalRow = layout.originalRowIndex(localRow);
RuntimeException error = result.rowFailures().get(localRow);
merged.set(
        originalRow,
        error == null
                ? result.values().valueAt(localRow)
                : EvaluationFailure.batch(
                        node.physicalNodeId(),
                        evaluationLocation(domain, originalRow, context),
                        error));
```

Increment the current node's operator failure count only for new Kernel failures, not inherited failures. Keep `batchRowCount` equal to the original evaluation size.

- [ ] **Step 5: Replace failed Batch elements at FEATURE_OUTPUT**

Extend the copy-on-write replacement helper to distinguish `EvaluationFailure` from ordinary empty values. With non-null `dft`, replace only failed/empty elements and increment fallback count for each replacement. With null `dft`, throw the first failure in original row order before values reach numeric widening or encoding.

- [ ] **Step 6: Run Batch, routing, and sequence-view tests**

Run:

```bash
mvn -Dtest=OperatorBatchFailureFallbackRuntimeTest,SequenceViewRuntimeTest,NativeBatchFailureRecoveryTest test
```

Expected: all tests pass; healthy Native calls preserve group identity and result order.

- [ ] **Step 7: Commit Batch isolation**

```bash
git add src/main/java/com/example/featuredag/runtime/DagRuntime.java src/test/java/com/example/featuredag/runtime/OperatorBatchFailureFallbackRuntimeTest.java
git commit -m "Isolate operator failures by batch row"
```

### Task 6: Preserve Recovery Through Specialized Execution

**Files:**
- Modify: `src/main/java/com/example/featuredag/runtime/SequenceKeyCountExecutor.java`
- Modify: `src/main/java/com/example/featuredag/physical/rewrite/CountAfterKeyedSequenceFilterRule.java`
- Test: `src/test/java/com/example/featuredag/runtime/SequenceKeyCountFailureRecoveryTest.java`
- Test: `src/test/java/com/example/featuredag/physical/rewrite/CountAfterKeyedSequenceFilterRuleTest.java`

**Interfaces:**
- Consumes: `EvaluationFailure` Batch markers from Tasks 4-5.
- Produces: Candidate/Request Batch outputs whose failure elements use the same runtime-only marker as generic operators.

- [ ] **Step 1: Write failing specialized recovery tests**

Register a synthetic `SequenceIndexProvider` whose `normalizeQueryKey("bad")` throws `IllegalArgumentException("bad key")`. Execute a specialized physical node with candidates `["a", "bad", "c"]` and a feature output default `-1`; assert results `[normalCount, -1, normalCount]`.

Add a provider whose `build` throws `IllegalStateException("index build failed")` for the second online request group. Assert every candidate in only that group uses `dft`, while the first group remains normal.

- [ ] **Step 2: Run specialized tests and verify RED**

Run:

```bash
mvn -Dtest=SequenceKeyCountFailureRecoveryTest,CountAfterKeyedSequenceFilterRuleTest test
```

Expected: `SequenceKeyCountExecutor` aborts at the first provider exception.

- [ ] **Step 3: Handle inherited external-input failures**

Before reading sequence/key inputs, recognize Single and Batch `EvaluationFailure` values. A failed request sequence marks every candidate in its request group; a failed candidate key marks only that candidate. Do not call the provider for inherited failures.

- [ ] **Step 4: Isolate provider computation failures**

For each request group:

- catch sequence index build failure and fill that group's candidate interval with one failure;
- catch key normalization per candidate and keep scanning later candidates;
- query counts only for healthy normalized keys;
- if `index.count(key)` fails, mark every candidate mapped to that key while keeping other keys healthy;
- never cache a failed index or count.

Use `EvaluationFailure.batch` with the same `evaluationLocation` format as generic runtime output.

- [ ] **Step 5: Run specialized and general Batch tests**

After the specialized tests pass, change the standard `CountAfterKeyedSequenceFilterRule` rewrite construction from `failureRecoverySupported=false` to `true`. This capability declaration and executor implementation must be committed together.

Run:

```bash
mvn -Dtest=SequenceKeyCountFailureRecoveryTest,CountAfterKeyedSequenceFilterRuleTest,OperatorBatchFailureFallbackRuntimeTest test
```

Expected: all tests pass and fusion/recovery capability assertions match the physical plan.

- [ ] **Step 6: Commit specialized recovery**

```bash
git add src/main/java/com/example/featuredag/runtime/SequenceKeyCountExecutor.java src/test/java/com/example/featuredag/runtime/SequenceKeyCountFailureRecoveryTest.java src/test/java/com/example/featuredag/physical/rewrite/CountAfterKeyedSequenceFilterRuleTest.java
git commit -m "Preserve defaults through specialized execution"
```

### Task 7: Map Public Errors and Expose Safe Recovery Counters

**Files:**
- Modify: `src/main/java/com/example/featuredag/api/FeatureDagEngine.java`
- Modify: `src/main/java/com/example/featuredag/runtime/NodeExecutionSnapshot.java`
- Test: `src/test/java/com/example/featuredag/api/DerivedFeatureOperatorFallbackTest.java`
- Test: `src/test/java/com/example/featuredag/api/OperatorFallbackObservabilityTest.java`

**Interfaces:**
- Consumes: `FeatureEvaluationException` and state counters from Task 4.
- Produces: public `FeatureGenerationException.featureName()` for no-default operator failures.
- Produces: `NodeExecutionSnapshot.operatorFailureCount()` and `fallbackCount()` without values, messages, or `Throwable`.

- [ ] **Step 1: Write failing public API tests**

Use standard JSON configs and public request types:

```java
@Test
public void publicOfflineBatchUsesDefaultForOnlyInvalidLogRow() {
    FeatureDagEngine engine = FeatureDagEngine.init(
            logConfigWithDefault(99.0),
            InitOptions.offline("operator-fallback-public"));

    OfflineBatchGenerateResult result = engine.generateBatch(
            new OfflineBatchGenerateRequest(
                    "public-batch",
                    List.of(
                            Map.of("score", List.of(4.0)),
                            Map.of("score", List.of(0.0)),
                            Map.of("score", List.of(8.0)))));

    assertEquals(List.of(2.0), result.rows().get(0).get("score_log"));
    assertEquals(List.of(99.0), result.rows().get(1).get("score_log"));
    assertEquals(List.of(3.0), result.rows().get(2).get("score_log"));
}

@Test
public void noDefaultPreservesFeatureNameAndOriginalCause() {
    FeatureDagEngine engine = FeatureDagEngine.init(
            logConfigWithoutDefault(),
            InitOptions.offline("operator-no-default"));

    FeatureGenerationException failure = assertThrows(
            FeatureGenerationException.class,
            () -> engine.generate(new OfflineGenerateRequest(
                    "invalid-log", Map.of("score", List.of(0.0)))));

    assertEquals("score_log", failure.featureName());
    assertTrue(rootCause(failure) instanceof IllegalArgumentException);
}
```

- [ ] **Step 2: Write failing observation-counter test**

Capture `ExecutionDiagnostics` with node detail enabled. Assert the failing operator snapshot has `operatorFailureCount == 1`, the feature output snapshot has `fallbackUsed == true` and `fallbackCount == 1`, and neither snapshot exposes the error message or `Throwable`.

- [ ] **Step 3: Run public tests and verify RED**

Run:

```bash
mvn -Dtest=DerivedFeatureOperatorFallbackTest,OperatorFallbackObservabilityTest,DerivedFeatureDefaultValueTest test
```

Expected: the public API either aborts or reports feature `null`, and snapshot counters are unavailable.

- [ ] **Step 4: Map feature-boundary failure in all generate entry points**

In the common `RuntimeException` wrapping path, extract `featureName` only from `FeatureEvaluationException`; preserve the internal exception as the public cause so its cause remains the original operator exception. Apply the same helper to single generate, Offline Batch, and Online Grouped Batch.

```java
private FeatureGenerationException generationFailure(
        RuntimeException error,
        String executionId) {
    String featureName = error instanceof FeatureEvaluationException featureFailure
            ? featureFailure.featureName()
            : null;
    return new FeatureGenerationException(
            error.getMessage(), planId, executionId, featureName, error);
}
```

- [ ] **Step 5: Add safe counters to observation snapshots**

Add `operatorFailureCount` and `fallbackCount` integer components to `NodeExecutionSnapshot`. Preserve its legacy convenience constructor by supplying zero for both new counters. Update `FeatureDagEngine.ExecutionObservation.snapshot` to copy the `RuntimeNodeState` counters created in Task 4.

- [ ] **Step 6: Run public API, observation, and source-boundary tests**

Run:

```bash
mvn -Dtest=DerivedFeatureOperatorFallbackTest,OperatorFallbackObservabilityTest,DerivedFeatureDefaultValueTest,SourceDefaultValueTest test
```

Expected: all tests pass; source binding failures remain fail-fast and do not consume derived `dft`.

- [ ] **Step 7: Commit API and diagnostics**

```bash
git add src/main/java/com/example/featuredag/api/FeatureDagEngine.java src/main/java/com/example/featuredag/runtime/NodeExecutionSnapshot.java src/test/java/com/example/featuredag/api
git commit -m "Expose operator fallback diagnostics"
```

### Task 8: Update Tracked Documentation and Verify the Repository

**Files:**
- Modify: `README.md`
- Modify: `docs/architecture/operator-single-batch-execution.md`
- Create: `docs/architecture/operator-failure-default-fallback.md`
- Do not modify: `docs/guides/operator-usage-guide.md` in the current checkout because it is part of an existing untracked user-owned tree.

**Interfaces:**
- Consumes: completed runtime behavior and names from Tasks 1-7.
- Produces: user-facing behavior, extension contract, recovery boundaries, and verification evidence.

- [ ] **Step 1: Update README behavior summary**

Document that derived non-null `dft` handles operator-kernel `RuntimeException` per Single/row/group/candidate; no `dft` rethrows; direct Registry calls remain fail-fast; source/config/decode/plan errors are excluded.

- [ ] **Step 2: Update Single/Batch extension architecture**

In `operator-single-batch-execution.md`, document `OperatorEvaluationResult`, Batch row failures, `RecoverableBatchOperatorKernel`, healthy-row projection, legacy Native fallback to Scalar Adapter, and the no-retry rule.

- [ ] **Step 3: Add focused runtime fallback architecture document**

Create `operator-failure-default-fallback.md` with the exact flow:

```text
Kernel RuntimeException
  -> EvaluationFailure
  -> downstream evaluation-unit short circuit
  -> FEATURE_OUTPUT
       -> non-null dft: replace and continue
       -> no dft: FeatureEvaluationException
```

Include the recoverable/non-recoverable table, Batch domain isolation, shared-feature defaults, side-effect caveat, cache exclusion, and counter names.

- [ ] **Step 4: Run all focused fallback tests**

Run:

```bash
mvn -Dtest=OperatorFailureRecoveryProtocolTest,NativeBatchFailureRecoveryTest,FailureRecoveryPlanningTest,DerivedFeatureOperatorFallbackRuntimeTest,OperatorBatchFailureFallbackRuntimeTest,SequenceKeyCountFailureRecoveryTest,DerivedFeatureOperatorFallbackTest,OperatorFallbackObservabilityTest test
```

Expected: all fallback protocol, planning, runtime, fusion, API, and observation tests pass.

- [ ] **Step 5: Run the mandatory repository self-test**

Run in Bash:

```bash
./scripts/run-self-test.sh
```

Expected: the `java -ea` legacy self-test phase passes, then all JUnit 4 tests pass.

- [ ] **Step 6: Build all deliverables**

Run:

```bash
mvn clean package
```

Expected: thin JAR and `target/feature-dag-engine-1.0.0-SNAPSHOT-all.jar` are produced and all JUnit 4 tests pass.

- [ ] **Step 7: Review the final diff and prove user files were preserved**

Run:

```bash
git diff --check
git status --short
git diff --stat HEAD~7..HEAD
```

Expected: no whitespace errors; only planned implementation, tests, and tracked documentation are committed; the user's pre-existing README/untracked Demo, scripts, tests, resources, document, and `docs/guides` changes remain outside implementation commits in the original checkout.

- [ ] **Step 8: Commit documentation**

```bash
git add README.md docs/architecture/operator-single-batch-execution.md docs/architecture/operator-failure-default-fallback.md
git commit -m "Document operator failure fallback"
```

## Completion Criteria

- Every operator Single Kernel runtime failure can reach a feature `dft` without a business-name branch.
- Every healthy Batch row/group/candidate remains ordered and evaluated exactly once.
- The four standard Native Batch kernels report per-row failures and retain Native routing.
- Legacy extension Native kernels use Scalar Adapter only on recovery-required paths.
- Unsupported specialized rewrites are excluded from recovery-required paths.
- Shared and nested expressions apply only the owning feature's `dft`.
- Missing `dft` preserves feature name, location, and original cause.
- Direct Registry, configuration, decode, source, plan, protocol, encoding, and `Error` behavior remains fail-fast.
- New counters are visible without values, messages, or throwable objects.
- Mandatory self-test and package commands pass.
