# Aligned List Sequences and Window Index Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Safely execute aligned raw Java List sequences, implement the three sequence operators required by the three-day app-count feature, and make fused industry counting respect `SequenceView` selections.

**Architecture:** Add a dedicated `ListSequenceValue` runtime handle so a sequence List is no longer confused with the online candidate axis. Treat all raw List sequences in one `ExecutionContext` as one aligned event batch identified by `executionId`, validate their source lengths, and propagate that identity to derived List sequences. Build fused industry indexes against the exact `SequenceValue` instance and use selection-aware cache keys.

**Tech Stack:** Java 21, Maven, dependency-free Java `assert` self-tests.

## Global Constraints

- Keep the existing `Map<String, Object>` request API and JSON configuration format unchanged.
- All raw List sequences in one `generate` call belong to one event batch and have equal source lengths.
- Do not add external runtime dependencies.
- Preserve online ITEM candidate vectorization and existing `SequenceBlock`/`SequenceView` behavior.
- Runtime caches remain scoped to one `ExecutionContext`; do not add cross-request caching.
- Follow four-space indentation, UTF-8, one public top-level type per file, and explicit imports.

---

### Task 1: Represent and validate aligned raw List sequences

**Files:**
- Create: `src/main/java/com/example/featuredag/runtime/ListSequenceValue.java`
- Modify: `src/main/java/com/example/featuredag/runtime/ValueHandle.java`
- Modify: `src/main/java/com/example/featuredag/runtime/ExecutionContext.java`
- Modify: `src/main/java/com/example/featuredag/runtime/DagRuntime.java`
- Modify: `src/main/java/com/example/featuredag/runtime/ExternalValueMaterializer.java`
- Test: `src/test/java/com/example/featuredag/DagEngineSelfTest.java`

**Interfaces:**
- Consumes: `ValueShape.SEQUENCE`, `ExecutionContext.executionId()`, ordinary raw `List<?>` values.
- Produces: `ListSequenceValue(String alignmentId, List<?> values)`, `alignmentId()`, `size()`, `values()`, and `ExecutionContext.registerRawSequence(String featureName, int size)`.

- [ ] **Step 1: Add failing runtime tests for aligned List sequences**

Invoke both new tests from `DagEngineSelfTest.main()`, then add:

```java
private static void testAlignedPlainListSequenceRuntime() {
    OperatorRegistry registry = OperatorRegistry.standard();
    LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry).build(
            List.of(
                    FeatureDefinition.raw(
                            "apps", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                    FeatureDefinition.raw(
                            "timestamps", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                    FeatureDefinition.derived(
                            "event_count", DataType.INT, "count(apps)", OutputPolicy.OUTPUT)),
            linkedSet("apps", "timestamps", "event_count"));
    PhysicalPlan plan = new PhysicalPlanner().plan(
            new LogicalDagOptimizer().analyze(dag),
            ExecutionEnvironment.OFFLINE,
            "aligned-list-sequences");
    ExecutionResult result = new DagRuntime(registry).execute(
            plan,
            ExecutionContext.offlineRow(
                    "aligned-row",
                    Map.of(
                            "apps", List.of("app0", "app1"),
                            "timestamps", List.of(20L, 10L))));

    ListSequenceValue apps = (ListSequenceValue) result.feature("apps");
    ListSequenceValue timestamps = (ListSequenceValue) result.feature("timestamps");
    assert apps.values().equals(List.of("app0", "app1")) : apps.values();
    assert timestamps.values().equals(List.of(20L, 10L)) : timestamps.values();
    assert apps.alignmentId().equals("aligned-row") : apps.alignmentId();
    assert timestamps.alignmentId().equals(apps.alignmentId());
    assert ((Number) result.feature("event_count").raw()).intValue() == 2;
}

private static void testMisalignedRawListSequenceLengthsFail() {
    OperatorRegistry registry = OperatorRegistry.standard();
    LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry).build(
            List.of(
                    FeatureDefinition.raw(
                            "apps", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                    FeatureDefinition.raw(
                            "timestamps", DataType.EVENT_SEQUENCE, EntityScope.USER, null)),
            linkedSet("apps", "timestamps"));
    PhysicalPlan plan = new PhysicalPlanner().plan(
            new LogicalDagOptimizer().analyze(dag),
            ExecutionEnvironment.OFFLINE,
            "misaligned-list-sequences");

    IllegalArgumentException error = expectThrows(
            IllegalArgumentException.class,
            () -> new DagRuntime(registry).execute(
                    plan,
                    ExecutionContext.offlineRow(
                            "misaligned-row",
                            Map.of(
                                    "apps", List.of("app0", "app1"),
                                    "timestamps", List.of(20L)))));
    assert error.getMessage().contains("apps") : error.getMessage();
    assert error.getMessage().contains("timestamps") : error.getMessage();
    assert error.getMessage().contains("expected=2") : error.getMessage();
    assert error.getMessage().contains("actual=1") : error.getMessage();
}
```

- [ ] **Step 2: Run the self-test and verify RED**

Run:

```powershell
mvn -q -DskipTests test-compile
```

Expected: compilation fails because `ListSequenceValue` does not exist. This is the intended missing runtime API, not a syntax failure in the test.

- [ ] **Step 3: Add `ListSequenceValue` and permit it as a handle**

Create `ListSequenceValue.java`:

```java
package com.example.featuredag.runtime;

import com.example.featuredag.logical.ValueShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class ListSequenceValue implements ValueHandle {
    private final String alignmentId;
    private final List<Object> values;

    public ListSequenceValue(String alignmentId, List<?> values) {
        this.alignmentId = requireText(alignmentId, "alignmentId");
        Objects.requireNonNull(values, "values");
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    public String alignmentId() { return alignmentId; }
    public int size() { return values.size(); }
    public List<Object> values() { return values; }
    @Override public ValueShape shape() { return ValueShape.SEQUENCE; }
    @Override public Object raw() { return values; }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String result = value.trim();
        if (result.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return result;
    }
}
```

Extend `ValueHandle`:

```java
public sealed interface ValueHandle
        permits ScalarValue, CandidateVectorValue, SequenceValue, IndexValue, ListSequenceValue {
```

- [ ] **Step 4: Register and validate raw source sequence lengths**

Add state and a package-visible method to `ExecutionContext`:

```java
private Integer rawSequenceLength;
private String firstRawSequenceFeature;

void registerRawSequence(String featureName, int size) {
    Objects.requireNonNull(featureName, "featureName");
    if (rawSequenceLength == null) {
        rawSequenceLength = size;
        firstRawSequenceFeature = featureName;
        return;
    }
    if (rawSequenceLength != size) {
        throw new IllegalArgumentException(
                "Raw sequence length mismatch: firstFeature=" + firstRawSequenceFeature
                        + ", feature=" + featureName
                        + ", expected=" + rawSequenceLength
                        + ", actual=" + size);
    }
}
```

Refactor `DagRuntime` so source wrapping is distinct from generic value wrapping:

```java
private static ValueHandle wrapSource(
        Object value,
        ValueShape logicalValueShape,
        String featureName,
        ExecutionContext context) {
    if (value instanceof ListSequenceValue sequence) {
        context.registerRawSequence(featureName, sequence.size());
        return sequence;
    }
    if (value instanceof ValueHandle handle) return handle;
    if (logicalValueShape == ValueShape.SEQUENCE && value instanceof List<?> list) {
        context.registerRawSequence(featureName, list.size());
        return new ListSequenceValue(context.executionId(), list);
    }
    return wrap(value, logicalValueShape, context.executionId());
}

private static ValueHandle wrap(
        Object value, ValueShape logicalValueShape, String alignmentId) {
    if (value instanceof ValueHandle handle) return handle;
    if (logicalValueShape == ValueShape.SEQUENCE && value instanceof List<?> list) {
        return new ListSequenceValue(alignmentId, list);
    }
    if (logicalValueShape == ValueShape.CANDIDATE_VECTOR && value instanceof List<?> list) {
        return new CandidateVectorValue(new ArrayList<>(list));
    }
    return new ScalarValue(value);
}
```

Use `wrapSource` for shared/default source values, keep the existing explicit online ITEM candidate branch, and pass `context.executionId()` when wrapping literals and non-vector generic-operator results. Change `vectorizedApply` to receive `ExecutionContext` so it can use that alignment id. Its candidate branch must continue returning `CandidateVectorValue` directly.

- [ ] **Step 5: Materialize List sequences at the public boundary**

Add this branch to `ExternalValueMaterializer.materialize` before `ScalarValue`:

```java
if (handle instanceof ListSequenceValue sequence) {
    return sequence.values().stream().map(this::materializeRaw).toList();
}
```

- [ ] **Step 6: Run the full self-test and verify GREEN**

Run:

```powershell
mvn -q -DskipTests test-compile dependency:build-classpath "-Dmdep.outputFile=target/test-classpath.txt"
$dagClasspath = "target\test-classes;target\classes;" + (Get-Content -Raw "target\test-classpath.txt")
java -ea -cp $dagClasspath com.example.featuredag.DagEngineSelfTest
```

Expected: `All DAG engine self tests passed.` Existing object-List and online candidate tests must remain green.

- [ ] **Step 7: Commit the aligned List runtime change**

```powershell
git add -- src/main/java/com/example/featuredag/runtime/ListSequenceValue.java src/main/java/com/example/featuredag/runtime/ValueHandle.java src/main/java/com/example/featuredag/runtime/ExecutionContext.java src/main/java/com/example/featuredag/runtime/DagRuntime.java src/main/java/com/example/featuredag/runtime/ExternalValueMaterializer.java src/test/java/com/example/featuredag/DagEngineSelfTest.java
git commit -m "Add aligned list sequence runtime values"
```

---

### Task 2: Implement the three sequence operators and the three-day feature

**Files:**
- Modify: `src/main/java/com/example/featuredag/operator/OperatorRegistry.java`
- Test: `src/test/java/com/example/featuredag/DagEngineSelfTest.java`

**Interfaces:**
- Consumes: raw immutable Lists exposed by `ListSequenceValue.raw()`, numeric `request_time`, and `{"margin": 259200}`.
- Produces: executable `greater_in_sequence_typed`, `list_index_typed`, and `find_list_index_typed` evaluators returning immutable Lists.

- [ ] **Step 1: Add failing evaluator and end-to-end tests**

Invoke `testWindowSequenceOperatorEvaluation()` and
`testThreeDayAppCountFromAlignedLists()` from `main()`, then add:

```java
private static void testWindowSequenceOperatorEvaluation() {
    OperatorRegistry registry = OperatorRegistry.standard();

    assert registry.evaluate(
            "greater_in_sequence_typed",
            List.of(List.of(20L, 15L, 10L), 20L, Map.of("margin", 10L)))
            .equals(List.of(0, 1));
    assert registry.evaluate(
            "list_index_typed",
            List.of(List.of("app0", "app1", "app2"), List.of(2, 0, 2)))
            .equals(List.of("app2", "app0", "app2"));
    assert registry.evaluate(
            "find_list_index_typed",
            List.of(List.of("app0", "app1", "app0"), "app0"))
            .equals(List.of(0, 2));
    assert registry.evaluate(
            "greater_in_sequence_typed",
            List.of(List.of(), 20L, Map.of("margin", 10L)))
            .equals(List.of());
    assert registry.evaluate(
            "list_index_typed",
            List.of(java.util.Arrays.asList("app0", null), List.of(1)))
            .equals(java.util.Arrays.asList((Object) null));
    assert registry.evaluate(
            "find_list_index_typed",
            java.util.Arrays.asList(java.util.Arrays.asList("app0", null), null))
            .equals(List.of(1));

    IllegalArgumentException negativeMargin = expectThrows(
            IllegalArgumentException.class,
            () -> registry.evaluate(
                    "greater_in_sequence_typed",
                    List.of(List.of(20L), 20L, Map.of("margin", -1))));
    assert negativeMargin.getMessage().contains("margin") : negativeMargin.getMessage();

    IllegalArgumentException missingMargin = expectThrows(
            IllegalArgumentException.class,
            () -> registry.evaluate(
                    "greater_in_sequence_typed",
                    List.of(List.of(20L), 20L, Map.of())));
    assert missingMargin.getMessage().contains("margin") : missingMargin.getMessage();

    IllegalArgumentException invalidElement = expectThrows(
            IllegalArgumentException.class,
            () -> registry.evaluate(
                    "greater_in_sequence_typed",
                    List.of(List.of("bad"), 20L, Map.of("margin", 10))));
    assert invalidElement.getMessage().contains("index 0") : invalidElement.getMessage();

    IllegalArgumentException nullElement = expectThrows(
            IllegalArgumentException.class,
            () -> registry.evaluate(
                    "greater_in_sequence_typed",
                    List.of(java.util.Arrays.asList((Object) null), 20L, Map.of("margin", 10))));
    assert nullElement.getMessage().contains("index 0") : nullElement.getMessage();

    IllegalArgumentException outOfBounds = expectThrows(
            IllegalArgumentException.class,
            () -> registry.evaluate(
                    "list_index_typed",
                    List.of(List.of("app0"), List.of(1))));
    assert outOfBounds.getMessage().contains("out of bounds") : outOfBounds.getMessage();

    IllegalArgumentException fractionalIndex = expectThrows(
            IllegalArgumentException.class,
            () -> registry.evaluate(
                    "list_index_typed",
                    List.of(List.of("app0", "app1"), List.of(0.5))));
    assert fractionalIndex.getMessage().contains("position 0")
            : fractionalIndex.getMessage();

    IllegalArgumentException nonList = expectThrows(
            IllegalArgumentException.class,
            () -> registry.evaluate(
                    "find_list_index_typed", List.of("not-a-list", "app0")));
    assert nonList.getMessage().contains("expects List") : nonList.getMessage();
}

private static void testThreeDayAppCountFromAlignedLists() {
    String json = """
            {
              "features": [
                {"name":"auid_app_time_seq","raw_name":"auid_app_time_seq",
                 "type":"EVENT_SEQUENCE","definition_type":"BASE",
                 "entity_scopes":["USER"],"value_shape":"SEQUENCE"},
                {"name":"timestamp","raw_name":"timestamp",
                 "type":"EVENT_SEQUENCE","definition_type":"BASE",
                 "entity_scopes":["USER"],"value_shape":"SEQUENCE"},
                {"name":"request_time","raw_name":"request_time","type":"INT",
                 "definition_type":"BASE","entity_scopes":["SCENE"],
                 "value_shape":"SCALAR"},
                {"name":"target_app","raw_name":"target_app","type":"STRING",
                 "definition_type":"BASE","entity_scopes":["USER"],
                 "value_shape":"SCALAR"},
                {"name":"auid_omnichannel_paid_cnt_3d","type":"INT",
                 "definition_type":"DERIVED",
                 "expression":"count(find_list_index_typed(list_index_typed(auid_app_time_seq, greater_in_sequence_typed(timestamp, request_time, {\"margin\":259200})), target_app))",
                 "output_policy":"OUTPUT","entity_scopes":["USER","SCENE"],
                 "value_shape":"SCALAR"}
              ],
              "feature_set_name":"three_day_app_count","version":"1"
            }
            """;
    FeatureDagEngine engine = FeatureDagEngine.init(
            json, InitOptions.offline("three-day-app-count"));
    GenerateResult result = engine.generate(new OfflineGenerateRequest(
            "auid-aaaa",
            Map.of(
                    "auid_app_time_seq",
                    List.of("app0", "app1", "app2", "app3", "app4"),
                    "timestamp",
                    List.of(1785549653L, 1785459831L, 1785286488L, 1785203315L, 1785114236L),
                    "request_time", 1785549653,
                    "target_app", "app0")));

    assert result.featureValues().get("auid_omnichannel_paid_cnt_3d").equals(1)
            : result.featureValues();
}
```

- [ ] **Step 2: Run the self-test and verify RED**

Run the PowerShell self-test command from Task 1 Step 6.

Expected: failure with `UnsupportedOperationException` naming
`greater_in_sequence_typed`; this proves the new evaluator and end-to-end path are not already implemented.

- [ ] **Step 3: Replace the three unsupported evaluators**

Register the operators with their existing arity and inference, but point them to these helpers:

```java
private static Object evaluateFindListIndexTyped(List<Object> args) {
    List<?> sequence = asList(args.get(0), "find_list_index_typed", "sequence");
    Object target = args.get(1);
    List<Integer> indices = new ArrayList<>();
    for (int index = 0; index < sequence.size(); index++) {
        if (Objects.equals(sequence.get(index), target)) indices.add(index);
    }
    return nullableImmutableList(indices);
}

private static Object evaluateListIndexTyped(List<Object> args) {
    List<?> sequence = asList(args.get(0), "list_index_typed", "sequence");
    List<?> indices = asList(args.get(1), "list_index_typed", "indices");
    List<Object> result = new ArrayList<>(indices.size());
    for (int position = 0; position < indices.size(); position++) {
        int index = asSequenceIndex(indices.get(position), position, sequence.size());
        result.add(sequence.get(index));
    }
    return nullableImmutableList(result);
}

private static Object evaluateGreaterInSequenceTyped(List<Object> args) {
    List<?> sequence = asList(args.get(0), "greater_in_sequence_typed", "sequence");
    Number base = asNumber(args.get(1));
    Map<?, ?> config = asMap(args.get(2));
    Object marginValue = config.get("margin");
    if (!(marginValue instanceof Number marginNumber)) {
        throw new IllegalArgumentException(
                "greater_in_sequence_typed requires numeric margin");
    }
    double margin = marginNumber.doubleValue();
    if (!Double.isFinite(margin) || margin < 0.0) {
        throw new IllegalArgumentException(
                "greater_in_sequence_typed margin must be finite and non-negative");
    }
    double threshold = base.doubleValue() - margin;
    List<Integer> indices = new ArrayList<>();
    for (int index = 0; index < sequence.size(); index++) {
        Object element = sequence.get(index);
        if (!(element instanceof Number number)) {
            throw new IllegalArgumentException(
                    "greater_in_sequence_typed requires numeric element at index " + index);
        }
        if (number.doubleValue() > threshold) indices.add(index);
    }
    return nullableImmutableList(indices);
}
```

Add strict helpers:

```java
private static List<?> asList(Object value, String operator, String argument) {
    if (value instanceof List<?> list) return list;
    throw new IllegalArgumentException(
            operator + " expects List for " + argument + ", got: "
                    + (value == null ? "null" : value.getClass().getName()));
}

private static int asSequenceIndex(Object value, int position, int sequenceSize) {
    if (!(value instanceof Number number)) {
        throw new IllegalArgumentException(
                "list_index_typed index at position " + position + " is not numeric: " + value);
    }
    double doubleValue = number.doubleValue();
    long longValue = number.longValue();
    if (!Double.isFinite(doubleValue) || doubleValue != longValue
            || longValue < 0 || longValue >= sequenceSize) {
        throw new IllegalArgumentException(
                "list_index_typed index at position " + position
                        + " is out of bounds: " + value + ", size=" + sequenceSize);
    }
    return (int) longValue;
}

private static <T> List<T> nullableImmutableList(List<T> values) {
    return java.util.Collections.unmodifiableList(new ArrayList<>(values));
}
```

- [ ] **Step 4: Run evaluator and end-to-end tests and verify GREEN**

Run the PowerShell self-test command from Task 1 Step 6.

Expected: all existing tests, direct evaluator cases, and the supplied three-day feature pass. The public output is scalar count `1`.

- [ ] **Step 5: Run the complete self-test and commit the operator change**

Run the PowerShell self-test command from Task 1 Step 6, then:

```powershell
git add -- src/main/java/com/example/featuredag/operator/OperatorRegistry.java src/test/java/com/example/featuredag/DagEngineSelfTest.java
git commit -m "Implement aligned sequence window operators"
```

Expected: `All DAG engine self tests passed.`

---

### Task 3: Make fused industry indexes selection-aware

**Files:**
- Modify: `src/main/java/com/example/featuredag/runtime/ExecutionContext.java`
- Modify: `src/main/java/com/example/featuredag/runtime/SequenceIndustryIndex.java`
- Modify: `src/main/java/com/example/featuredag/runtime/DagRuntime.java`
- Test: `src/test/java/com/example/featuredag/DagEngineSelfTest.java`

**Interfaces:**
- Consumes: any `SequenceValue`, including `SequenceBlock` and `SequenceView`.
- Produces: `SequenceIndustryIndex.build(SequenceValue sequence)` and request-local cache keys that distinguish concrete View instances.

- [ ] **Step 1: Add a failing regression test with two Views over one base block**

Invoke `testFusedIndustryCountsRespectSequenceViews()` from `main()` and add:

```java
private static void testFusedIndustryCountsRespectSequenceViews() {
    OperatorRegistry registry = OperatorRegistry.standard();
    LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry).build(
            List.of(
                    FeatureDefinition.raw(
                            "first_view", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                    FeatureDefinition.raw(
                            "second_view", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                    FeatureDefinition.raw(
                            "item_industry", DataType.STRING, EntityScope.ITEM, "unknown"),
                    FeatureDefinition.derived(
                            "first_count", DataType.INT,
                            "count(extractIndustry(first_view, item_industry))",
                            OutputPolicy.OUTPUT),
                    FeatureDefinition.derived(
                            "second_count", DataType.INT,
                            "count(extractIndustry(second_view, item_industry))",
                            OutputPolicy.OUTPUT)),
            linkedSet("first_count", "second_count"));
    PhysicalPlan plan = new PhysicalPlanner().plan(
            new LogicalDagOptimizer().analyze(dag),
            ExecutionEnvironment.ONLINE,
            "view-aware-fusion");
    assert plan.nodes().stream()
            .filter(node -> node.executorType() == ExecutorType.COUNT_INDUSTRY_BATCH)
            .count() == 2 : new PhysicalPlanPrinter().print(plan);

    SequenceBlock base = sequence();
    SequenceView first = SequenceView.slice(base, 0, 2);
    SequenceView second = SequenceView.slice(base, 2, 6);
    ExecutionResult result = new DagRuntime(registry).execute(
            plan,
            ExecutionContext.onlineRequest(
                    "view-aware-request",
                    Map.of("first_view", first, "second_view", second),
                    List.of(Map.of("item_industry", "industry1"))));

    CandidateVectorValue firstCount =
            (CandidateVectorValue) result.feature("first_count");
    CandidateVectorValue secondCount =
            (CandidateVectorValue) result.feature("second_count");
    assert firstCount.values().equals(List.of(1)) : firstCount.values();
    assert secondCount.values().equals(List.of(2)) : secondCount.values();
}
```

- [ ] **Step 2: Run the self-test and verify RED**

Run the PowerShell self-test command from Task 1 Step 6.

Expected: the new assertions fail because both fused nodes count all three `industry1` events from the shared base block.

- [ ] **Step 3: Build the industry index from the exact SequenceValue**

Change `SequenceIndustryIndex` to:

```java
public static IndexValue build(SequenceValue sequence) {
    SequenceBlock base = sequence.baseBlock();
    Map<String, List<Integer>> temp = new LinkedHashMap<>();
    for (int logicalIndex = 0; logicalIndex < sequence.size(); logicalIndex++) {
        int baseIndex = sequence.baseIndexAt(logicalIndex);
        temp.computeIfAbsent(
                base.industryAtBaseIndex(baseIndex),
                ignored -> new ArrayList<>()).add(baseIndex);
    }
    Map<String, int[]> result = new LinkedHashMap<>();
    for (Map.Entry<String, List<Integer>> entry : temp.entrySet()) {
        result.put(
                entry.getKey(),
                entry.getValue().stream().mapToInt(Integer::intValue).toArray());
    }
    return new IndexValue(result);
}
```

- [ ] **Step 4: Use typed, View-specific runtime cache keys**

Change `ExecutionContext.cacheRegistry` and its getter to `Map<Object, Object>`. Add private records inside `DagRuntime`:

```java
private record IndustryIndexCacheKey(SequenceValue sequence) {}

private record CandidateCountCacheKey(
        String physicalNodeId,
        SequenceValue sequence,
        String industry) {}
```

Then update `executeCountIndustryBatch`:

```java
SequenceValue sequence = (SequenceValue) sequenceRaw;
Object indexKey = new IndustryIndexCacheKey(sequence);
IndexValue index;
Object cachedIndex = context.cacheRegistry().get(indexKey);
if (cachedIndex instanceof IndexValue cached) {
    index = cached;
    state.markCacheHit("REQUEST_INDEX");
} else {
    index = SequenceIndustryIndex.build(sequence);
    context.cacheRegistry().put(indexKey, index);
}

for (String industry : uniqueIndustries) {
    Object cacheKey = new CandidateCountCacheKey(
            node.physicalNodeId(), sequence, industry);
    // Preserve the existing Integer lookup, markCacheHit, put, and result mapping.
}
```

Do not call `baseBlock()` to choose the counting boundary. The record keys intentionally use normal object identity because `SequenceBlock` and `SequenceView` do not override `equals`; two different Views over one base therefore cannot collide.

- [ ] **Step 5: Run the full self-test and verify GREEN**

Run the PowerShell self-test command from Task 1 Step 6.

Expected: both View counts are correct (`1` and `2`), all original fusion/dedup tests pass, and the output ends with `All DAG engine self tests passed.`

- [ ] **Step 6: Commit the selection-aware fusion change**

```powershell
git add -- src/main/java/com/example/featuredag/runtime/ExecutionContext.java src/main/java/com/example/featuredag/runtime/SequenceIndustryIndex.java src/main/java/com/example/featuredag/runtime/DagRuntime.java src/test/java/com/example/featuredag/DagEngineSelfTest.java
git commit -m "Respect sequence views in fused counts"
```

---

### Task 4: Document support and run release-level verification

**Files:**
- Modify: `README.md`
- Verify: `src/test/java/com/example/featuredag/DagEngineSelfTest.java`

**Interfaces:**
- Consumes: completed runtime and operator behavior from Tasks 1-3.
- Produces: accurate operator-support documentation and fresh build/self-test evidence.

- [ ] **Step 1: Update the runtime support table and sequence input contract**

In README `已支持 Runtime 计算`, add:

```markdown
| `greater_in_sequence_typed` | `greater_in_sequence_typed(seq, base, {"margin": m})` | 返回大于 `base - margin` 的元素索引 |
| `list_index_typed` | `list_index_typed(seq, indices)` | 按索引抽取列表元素 |
| `find_list_index_typed` | `find_list_index_typed(seq, target)` | 返回所有等于目标值的位置 |
```

Remove those three names from the unsupported sequence-operator row. Add this input-boundary note:

```markdown
普通 Java `List` 可作为 `value_shape=SEQUENCE` 的原始输入。同一次 `generate`
中的所有原始 List 序列必须属于同一事件批次且长度一致；Runtime 会再次校验长度。
在线 ITEM 候选轴仍使用 `CandidateVectorValue`，不会与用户序列混淆。
```

Document that fused industry counting respects the exact `SequenceView` and that its index/count caches remain request-local.

- [ ] **Step 2: Run fresh full verification**

Run:

```powershell
mvn clean package
mvn -q -DskipTests test-compile dependency:build-classpath "-Dmdep.outputFile=target/test-classpath.txt"
$dagClasspath = "target\test-classes;target\classes;" + (Get-Content -Raw "target\test-classpath.txt")
java -ea -cp $dagClasspath com.example.featuredag.DagEngineSelfTest
git diff --check
git status --short
```

Expected:

- both Maven commands exit `0`;
- self-test prints `All DAG engine self tests passed.`;
- `git diff --check` prints nothing;
- `git status --short` lists only the intended README change before the final commit.

- [ ] **Step 3: Commit the documentation change**

```powershell
git add -- README.md
git commit -m "Document aligned sequence runtime support"
```

- [ ] **Step 4: Record final repository state**

Run:

```powershell
git status --short
git log -5 --oneline
```

Expected: working tree is clean and the latest commits correspond to the aligned List runtime, window operators, selection-aware fusion, and documentation.
