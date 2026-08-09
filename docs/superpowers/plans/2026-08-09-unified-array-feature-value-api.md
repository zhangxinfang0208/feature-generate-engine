# Unified Array Feature Value API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the public `generate` boundary with array-only feature values while preserving internal scalar, sequence, and candidate-vector execution semantics.

**Architecture:** Add schema-driven input and output adapters at the `FeatureDagEngine` boundary. Requests and results expose `Map<String, List<?>>`; the input adapter unwraps non-sequence sources and preserves sequence Lists before creating `ExecutionContext`, while the output adapter uses logical output shape to wrap scalars and preserve sequences. The existing Runtime remains natural-value based, except that the global raw-sequence length restriction is removed.

**Tech Stack:** Java 21, Maven, Jackson 2.21.3, dependency-free Java `assert` self-tests.

## Global Constraints

- Public Java feature values use `List<?>`; model-specific `long[]` remains outside this repository.
- The new public API is intentionally breaking and does not accept legacy bare scalar values.
- `value_shape` remains required execution metadata; no new public-input shape/type validation pass is added in v1.
- V1 does not add `alignment_group` or cross-sequence alignment validation.
- V1 removes the current global equality check across all raw List sequence lengths.
- Existing logical-DAG operator shape inference and derived declaration validation remain enabled.
- Legacy values such as `["1|0|1|v2"]` must be normalized before calling this engine.
- Runtime returns no partial feature results and performs no node-level fallback.
- Preserve Java 21, UTF-8, four-space indentation, explicit imports, and the existing package structure.
- Do not add dependencies; tests continue to use Java assertions rather than JUnit.

---

## File Structure

### New production files

- `src/main/java/com/example/featuredag/api/FeatureValueCollections.java`: nullable-safe immutable List/Map copying shared by request and result DTOs.
- `src/main/java/com/example/featuredag/api/FeatureInputDecoder.java`: compiles reachable source binding/shape/scope metadata from `LogicalDag` and decodes array-only request maps into natural Runtime maps.
- `src/main/java/com/example/featuredag/api/FeatureOutputEncoder.java`: compiles logical output shapes and converts shared/candidate Runtime values into public Lists.

### New test file

- `src/test/java/com/example/featuredag/api/FeatureValueCodecSelfTest.java`: focused assertion tests for nullable copying, source decoding, ignored fields, and scalar/sequence output encoding.

### Modified production files

- `src/main/java/com/example/featuredag/api/OfflineGenerateRequest.java`: expose `Map<String, List<?>>` and copy each value List.
- `src/main/java/com/example/featuredag/api/OnlineGenerateRequest.java`: expose array-only shared and candidate maps and copy nested containers.
- `src/main/java/com/example/featuredag/api/GenerateResult.java`: expose array-only shared and candidate output maps.
- `src/main/java/com/example/featuredag/api/FeatureDagEngine.java`: install the codecs at initialization and use them around `ExecutionContext`/`ExecutionResult`.
- `src/main/java/com/example/featuredag/runtime/CandidateVectorValue.java`: preserve null candidate elements with a nullable-safe immutable copy.
- `src/main/java/com/example/featuredag/runtime/ExecutionContext.java`: remove execution-global raw sequence length state and registration.
- `src/main/java/com/example/featuredag/runtime/DagRuntime.java`: stop registering every raw List sequence against one global length.
- `src/main/java/com/example/featuredag/demo/DagDemo.java`: use single-element Lists for public scalars and show array output.

### Modified tests and documentation

- `src/test/java/com/example/featuredag/DagEngineSelfTest.java`: call the codec self-test, migrate all public API fixtures/assertions, use plain typed Lists for public online sequence scenarios, and replace the global mismatch rejection test.
- `README.md`: document the array-only API, upstream legacy normalization, online placement, and v1 alignment limitation.
- `AGENTS.md`: replace the old “auid is a bare String” Demo contract with the array-only contract.

---

### Task 1: Add schema-driven boundary codecs

**Files:**
- Create: `src/main/java/com/example/featuredag/api/FeatureValueCollections.java`
- Create: `src/main/java/com/example/featuredag/api/FeatureInputDecoder.java`
- Create: `src/main/java/com/example/featuredag/api/FeatureOutputEncoder.java`
- Create: `src/test/java/com/example/featuredag/api/FeatureValueCodecSelfTest.java`
- Modify: `src/test/java/com/example/featuredag/DagEngineSelfTest.java:77-116`

**Interfaces:**
- Consumes: `LogicalDag.nodes()`, `LogicalDag.featureOutput(String)`, `SourceNode.sourceBinding()`, `SourceNode.valueShape()`, `SourceNode.entityScopes()`, `ExternalValueMaterializer`.
- Produces: `FeatureInputDecoder.from(LogicalDag)`, `decodeOffline`, `decodeOnlineShared`, `decodeOnlineCandidates`; `FeatureOutputEncoder.from(LogicalDag)`, `encode`, `encodeCandidateElement`; nullable-safe collection helpers used by later API DTO changes.

- [ ] **Step 1: Add a focused failing codec self-test**

Create `FeatureValueCodecSelfTest` in package `com.example.featuredag.api` so it can exercise package-private adapters. Use a raw scalar, shared sequence, and ITEM scalar in one logical DAG:

```java
package com.example.featuredag.api;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.FeatureDefinition;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.logical.ValueShape;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.runtime.ListSequenceValue;
import com.example.featuredag.runtime.ScalarValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FeatureValueCodecSelfTest {
    private FeatureValueCodecSelfTest() {}

    public static void run() {
        List<FeatureDefinition> definitions = List.of(
                FeatureDefinition.builder()
                        .name("request_time")
                        .role(com.example.featuredag.definition.FeatureRole.RAW)
                        .dataType(DataType.INT)
                        .addEntityScope(EntityScope.SCENE)
                        .sourceBinding("request_time")
                        .declaredValueShape(ValueShape.SCALAR)
                        .build(),
                FeatureDefinition.builder()
                        .name("ratings")
                        .role(com.example.featuredag.definition.FeatureRole.RAW)
                        .dataType(DataType.INT)
                        .addEntityScope(EntityScope.USER)
                        .sourceBinding("ratings")
                        .declaredValueShape(ValueShape.SEQUENCE)
                        .build(),
                FeatureDefinition.builder()
                        .name("category")
                        .role(com.example.featuredag.definition.FeatureRole.RAW)
                        .dataType(DataType.STRING)
                        .addEntityScope(EntityScope.ITEM)
                        .sourceBinding("category")
                        .declaredValueShape(ValueShape.SCALAR)
                        .build(),
                FeatureDefinition.builder()
                        .name("payload")
                        .role(com.example.featuredag.definition.FeatureRole.RAW)
                        .dataType(DataType.OBJECT)
                        .addEntityScope(EntityScope.SCENE)
                        .sourceBinding("payload")
                        .declaredValueShape(ValueShape.OBJECT)
                        .build());
        LogicalDag dag = new LogicalDagBuilder(
                new ExpressionParser(), OperatorRegistry.standard())
                .build(definitions, Set.of("request_time", "ratings", "category", "payload"));

        FeatureInputDecoder decoder = FeatureInputDecoder.from(dag);
        Map<String, Object> offline = decoder.decodeOffline(Map.of(
                "request_time", List.of(100L),
                "ratings", List.of(1L, 0L, 1L),
                "ignored", List.of("unused")));
        assert offline.equals(Map.of(
                "request_time", 100L,
                "ratings", List.of(1L, 0L, 1L))) : offline;

        Map<String, Object> shared = decoder.decodeOnlineShared(Map.of(
                "request_time", List.of(100L),
                "ratings", List.of(1L, 0L, 1L)));
        assert !shared.containsKey("category") : shared;
        List<Map<String, Object>> candidates = decoder.decodeOnlineCandidates(List.of(
                Map.of("category", List.of("tech")),
                Map.of("category", List.of("sports"))));
        assert candidates.equals(List.of(
                Map.of("category", "tech"),
                Map.of("category", "sports"))) : candidates;

        FeatureOutputEncoder encoder = FeatureOutputEncoder.from(dag);
        List<Object> nullable = new ArrayList<>();
        nullable.add(null);
        List<?> nullOutput = encoder.encode("request_time", new ScalarValue(null));
        assert nullOutput.size() == 1 && nullOutput.getFirst() == null : nullOutput;
        assert encoder.encode(
                "ratings", new ListSequenceValue("codec-test", List.of(1L, 0L)))
                .equals(List.of(1L, 0L));
        assert encoder.encode(
                "payload", new ScalarValue(List.of("nested")))
                .equals(List.of(List.of("nested")));

        List<?> copied = FeatureValueCollections.immutableList(nullable);
        nullable.set(0, "changed");
        assert copied.getFirst() == null : copied;
        assert Collections.unmodifiableList(new ArrayList<>(copied)).equals(copied);
    }
}
```

Call `FeatureValueCodecSelfTest.run()` near the start of `DagEngineSelfTest.main`.

- [ ] **Step 2: Run the self-test to verify the new test fails**

Run:

```bash
./scripts/run-self-test.sh
```

Expected: test compilation fails because `FeatureInputDecoder`, `FeatureOutputEncoder`, and `FeatureValueCollections` do not exist.

- [ ] **Step 3: Implement nullable-safe collection copying**

Create `FeatureValueCollections` as a package-private utility. It must reject null feature Lists while preserving null elements:

```java
final class FeatureValueCollections {
    private FeatureValueCollections() {}

    static List<?> immutableList(List<?> values) {
        Objects.requireNonNull(values, "feature values");
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    static List<?> singleton(Object value) {
        List<Object> result = new ArrayList<>(1);
        result.add(value);
        return Collections.unmodifiableList(result);
    }

    static Map<String, List<?>> immutableFeatureMap(Map<String, ? extends List<?>> values) {
        Objects.requireNonNull(values, "feature values");
        Map<String, List<?>> result = new LinkedHashMap<>();
        values.forEach((name, featureValues) -> result.put(
                Objects.requireNonNull(name, "feature name"), immutableList(featureValues)));
        return Collections.unmodifiableMap(result);
    }

    static List<Map<String, List<?>>> immutableCandidates(
            List<? extends Map<String, ? extends List<?>>> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        return candidates.stream()
                .map(FeatureValueCollections::immutableFeatureMap)
                .toList();
    }
}
```

Use explicit imports for `ArrayList`, `Collections`, `LinkedHashMap`, `List`, `Map`, and `Objects`.

- [ ] **Step 4: Implement `FeatureInputDecoder`**

Compile reachable source descriptors by `sourceBinding`. Offline decoding uses every descriptor; online shared decoding excludes sources whose scopes contain `ITEM`; online candidate decoding includes only ITEM sources.

```java
final class FeatureInputDecoder {
    private record SourceSpec(String sourceBinding, ValueShape shape, boolean itemScoped) {}

    private final List<SourceSpec> sources;

    private FeatureInputDecoder(List<SourceSpec> sources) {
        this.sources = List.copyOf(sources);
    }

    static FeatureInputDecoder from(LogicalDag dag) {
        List<SourceSpec> sources = dag.orderedNodes().stream()
                .filter(SourceNode.class::isInstance)
                .map(SourceNode.class::cast)
                .map(source -> new SourceSpec(
                        source.sourceBinding(),
                        source.valueShape(),
                        source.entityScopes().contains(EntityScope.ITEM)))
                .toList();
        return new FeatureInputDecoder(sources);
    }

    Map<String, Object> decodeOffline(Map<String, List<?>> external) {
        return decode(external, sources);
    }

    Map<String, Object> decodeOnlineShared(Map<String, List<?>> external) {
        return decode(external, sources.stream().filter(source -> !source.itemScoped()).toList());
    }

    List<Map<String, Object>> decodeOnlineCandidates(
            List<Map<String, List<?>>> externalCandidates) {
        List<SourceSpec> itemSources = sources.stream().filter(SourceSpec::itemScoped).toList();
        return externalCandidates.stream().map(values -> decode(values, itemSources)).toList();
    }

    private static Map<String, Object> decode(
            Map<String, List<?>> external,
            List<SourceSpec> sources) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (SourceSpec source : sources) {
            if (!external.containsKey(source.sourceBinding())) continue;
            List<?> values = external.get(source.sourceBinding());
            Object decoded = source.shape() == ValueShape.SEQUENCE
                    ? FeatureValueCollections.immutableList(values)
                    : values.getFirst();
            result.put(source.sourceBinding(), decoded);
        }
        return result;
    }
}
```

Do not add element-type, scalar-cardinality, or alignment validation. Empty scalar Lists fail naturally at `getFirst`; extra scalar elements are ignored by the v1 contract.

- [ ] **Step 5: Implement `FeatureOutputEncoder`**

Store `featureName -> inferred ValueShape` from the logical feature output nodes. Shared handles and candidate elements use the same shape-aware wrapper:

```java
final class FeatureOutputEncoder {
    private final Map<String, ValueShape> outputShapes;
    private final ExternalValueMaterializer materializer = new ExternalValueMaterializer();

    private FeatureOutputEncoder(Map<String, ValueShape> outputShapes) {
        this.outputShapes = Map.copyOf(outputShapes);
    }

    static FeatureOutputEncoder from(LogicalDag dag) {
        Map<String, ValueShape> shapes = new LinkedHashMap<>();
        dag.featureOutputNodeIds().keySet().forEach(featureName ->
                shapes.put(featureName, dag.featureOutput(featureName).valueShape()));
        return new FeatureOutputEncoder(shapes);
    }

    List<?> encode(String featureName, ValueHandle handle) {
        return encodeMaterialized(featureName, materializer.materialize(handle));
    }

    List<?> encodeCandidateElement(String featureName, Object value) {
        return encodeMaterialized(featureName, materializer.materializeRaw(value));
    }

    private List<?> encodeMaterialized(String featureName, Object value) {
        ValueShape shape = Objects.requireNonNull(
                outputShapes.get(featureName), "Unknown output feature: " + featureName);
        if (shape == ValueShape.SEQUENCE) {
            if (!(value instanceof List<?> list)) {
                throw new IllegalStateException(
                        "Sequence output did not materialize as List: " + featureName);
            }
            return FeatureValueCollections.immutableList(list);
        }
        return FeatureValueCollections.singleton(value);
    }
}
```

Use `ValueShape`, not `instanceof List`, so scalar OBJECT values that contain a List become `[[...]]` externally.
Import `ExternalValueMaterializer` and `ValueHandle` from `com.example.featuredag.runtime`, and import
`LogicalDag`, `ValueShape`, `LinkedHashMap`, `List`, `Map`, and `Objects` explicitly. For
`FeatureInputDecoder`, explicitly import `EntityScope`, `LogicalDag`, `SourceNode`, `ValueShape`,
`LinkedHashMap`, `List`, and `Map`.

- [ ] **Step 6: Run the self-test and commit the codec unit**

Run:

```bash
./scripts/run-self-test.sh
```

Expected: `All DAG engine self tests passed.`

Commit:

```bash
git add src/main/java/com/example/featuredag/api/FeatureValueCollections.java \
  src/main/java/com/example/featuredag/api/FeatureInputDecoder.java \
  src/main/java/com/example/featuredag/api/FeatureOutputEncoder.java \
  src/test/java/com/example/featuredag/api/FeatureValueCodecSelfTest.java \
  src/test/java/com/example/featuredag/DagEngineSelfTest.java
git commit -m "Add array feature value codecs"
```

---

### Task 2: Migrate the public API and end-to-end behavior

**Files:**
- Modify: `src/main/java/com/example/featuredag/api/OfflineGenerateRequest.java`
- Modify: `src/main/java/com/example/featuredag/api/OnlineGenerateRequest.java`
- Modify: `src/main/java/com/example/featuredag/api/GenerateResult.java`
- Modify: `src/main/java/com/example/featuredag/api/FeatureDagEngine.java`
- Modify: `src/main/java/com/example/featuredag/runtime/CandidateVectorValue.java`
- Modify: `src/main/java/com/example/featuredag/demo/DagDemo.java`
- Modify: `src/test/java/com/example/featuredag/DagEngineSelfTest.java`

**Interfaces:**
- Consumes: Task 1 codecs and nullable collection helpers.
- Produces: array-only request/result signatures and a complete offline/online `FeatureDagEngine.generate` path using internal natural values.

- [ ] **Step 1: Change the three-day and public API tests to the final array contract**

Update `testThreeDayAppCountFromAlignedLists` first so every scalar input is a single-element List and the scalar output is `[1]`:

```java
GenerateResult result = engine.generate(new OfflineGenerateRequest(
        "auid-aaaa",
        Map.of(
                "auid_app_time_seq", List.of("app0", "app1", "app2", "app3", "app4"),
                "timestamp", List.of(
                        1785549653L, 1785459831L, 1785286488L,
                        1785203315L, 1785114236L),
                "request_time", List.of(1785549653),
                "target_app", List.of("app0"))));
assert result.featureValues().get("auid_omnichannel_paid_cnt_3d").equals(List.of(1))
        : result.featureValues();
```

Update `testOfflinePublicApi` similarly:

```java
Map<String, List<?>> row = new LinkedHashMap<>();
row.put("raw_price", List.of(100.0));
row.put("quality_score", List.of(0.8));
GenerateResult result = engine.generate(new OfflineGenerateRequest("row-1", row));
double score = ((Number) result.featureValues()
        .get("price_score_out").getFirst()).doubleValue();
assert Math.abs(score - 0.08) < 0.000001 : result.featureValues();
```

Add this helper near the existing test helpers and use it for scalar public outputs:

```java
private static Object scalarFeature(Map<String, List<?>> values, String name) {
    List<?> featureValues = values.get(name);
    assert featureValues != null : "Missing feature " + name + " in " + values;
    assert featureValues.size() == 1 : "Expected scalar array for " + name + ": " + featureValues;
    return featureValues.getFirst();
}
```

Invoke the test command now.

- [ ] **Step 2: Run the self-test to verify the API migration tests fail**

Run:

```bash
./scripts/run-self-test.sh
```

Expected: compilation fails because the request/result DTOs still expose `Map<String, Object>`, or runtime fails because scalar Lists are not decoded.

- [ ] **Step 3: Replace request and result DTO signatures**

In `OfflineGenerateRequest` use:

```java
private final Map<String, List<?>> rowValues;

public OfflineGenerateRequest(String executionId, Map<String, List<?>> rowValues) {
    this.executionId = requireText(executionId, "executionId");
    this.rowValues = FeatureValueCollections.immutableFeatureMap(rowValues);
}

public Map<String, List<?>> rowValues() { return rowValues; }
```

In `OnlineGenerateRequest` use:

```java
private final Map<String, List<?>> sharedValues;
private final List<Map<String, List<?>>> candidates;

public OnlineGenerateRequest(
        String executionId,
        Map<String, List<?>> sharedValues,
        List<Map<String, List<?>>> candidates) {
    this.executionId = requireText(executionId, "executionId");
    this.sharedValues = FeatureValueCollections.immutableFeatureMap(sharedValues);
    this.candidates = FeatureValueCollections.immutableCandidates(candidates);
}
```

In `GenerateResult`, change both public maps to `Map<String, List<?>>` and copy them with the same helper:

```java
private final Map<String, List<?>> featureValues;
private final List<Map<String, List<?>>> candidateFeatureValues;
```

Delete now-unused imports for direct `Collections`/`LinkedHashMap` copying from these DTOs.

- [ ] **Step 4: Install codecs in `FeatureDagEngine`**

Add immutable fields and constructor arguments:

```java
private final FeatureInputDecoder inputDecoder;
private final FeatureOutputEncoder outputEncoder;
```

In `initialize`, construct both from the already-built logical DAG and pass them to the engine constructor:

```java
FeatureInputDecoder inputDecoder = FeatureInputDecoder.from(dag);
FeatureOutputEncoder outputEncoder = FeatureOutputEncoder.from(dag);
return new FeatureDagEngine(
        options.environment(), mapped, planId, plan, new DagRuntime(operators),
        inputDecoder, outputEncoder);
```

Decode before creating execution contexts:

```java
ExecutionContext.offlineRow(
        request.executionId(), inputDecoder.decodeOffline(request.rowValues()))
```

```java
ExecutionContext.onlineRequest(
        request.executionId(),
        inputDecoder.decodeOnlineShared(request.sharedValues()),
        inputDecoder.decodeOnlineCandidates(request.candidates()))
```

Build typed result maps and encode with the inferred output shape:

```java
Map<String, List<?>> result = new LinkedHashMap<>();
result.put(output.storeName(), outputEncoder.encode(output.featureName(), value));
```

For online candidate vectors:

```java
candidateResults.get(index).put(
        output.storeName(),
        outputEncoder.encodeCandidateElement(
                output.featureName(), vector.valueAt(index)));
```

For non-vector online outputs use `outputEncoder.encode` and place the List in shared results.

- [ ] **Step 5: Preserve null elements in `CandidateVectorValue`**

Replace `List.copyOf(values)` with a nullable-safe immutable copy:

```java
public CandidateVectorValue {
    values = Collections.unmodifiableList(new ArrayList<>(values));
}
```

Add explicit `ArrayList` and `Collections` imports. This allows an ITEM scalar represented externally as `[null]` to survive candidate collection until the operator or output boundary decides how to handle it.

- [ ] **Step 6: Migrate the offline public fixtures and compile-time Demo call**

Use `rg -n 'new OfflineGenerateRequest' src` and migrate all 12 call sites. The affected self-test groups are
`testThreeDayAppCountFromAlignedLists`, `testOfflinePublicApi`, `testConfigPathInit`,
`testOfflineSequenceMaterialization`, `testConfigurationAndRequestValidation`,
`assertDefaultDerivedOutputPolicy`, `testEmptySequenceAndOfflineOutputSet`, and
`testOfflineOnlineConsistency`; the twelfth site is `DagDemo`.

- SCALAR source: `value -> List.of(value)`;
- nullable SCALAR: `Collections.singletonList(null)`;
- plain SEQUENCE source: keep its element List unchanged;
- scalar result assertion: read `resultMap.get(name).getFirst()` or `scalarFeature(...)`;
- sequence result assertion: read the output List directly.

Replace `testOfflineSequenceMaterialization` with a public plain-List sequence output case using a
`STRING + SEQUENCE` source and `coalesce(user_seq1, [])`. Assert that the public output equals the
source List. Keep low-level `DagRuntime`/`ExecutionContext` tests on natural values.

Update `DagDemo` only enough for compilation in this task: declare `Map<String, List<?>> row`, wrap
`auid`, `request_time`, and `target_app`, and read the request time from the first element. Task 4 changes
the sequence element types and final displayed contract.

- [ ] **Step 7: Migrate the online public fixtures and typed sequence config**

Use `rg -n 'new OnlineGenerateRequest' src` and migrate all 13 call sites. Cover
`testOnlinePublicApi`, `testOnlineEngineConcurrentReuse`, the request-mode validation case,
`testCandidateCardinalityAndDefaults`, `testEmptySequenceAndOfflineOutputSet`, and
`testOfflineOnlineConsistency`, including the `fourCandidates` and `mergedRow` helpers.

- shared SCALAR source: use a single-element List;
- shared SEQUENCE source: keep the complete element List;
- candidate ITEM scalar: use a single-element List inside each candidate Map;
- candidate scalar result: each candidate Map contains a single-element List;
- retain assertions for empty, one, four, reordered, defaulted, and missing candidates.

Change `onlineConfigJson()` from specialized event objects to a plain typed industry sequence:

```json
{"name":"user_seq1","raw_name":"user_seq1","type":"STRING",
 "definition_type":"BASE","entity_scopes":["USER"],"value_shape":"SEQUENCE"}
```

Change `same_industry_seq` to an integer index sequence:

```json
{
  "name":"same_industry_seq",
  "type":"INT",
  "definition_type":"DERIVED",
  "expression":"find_list_index_typed(user_seq1, item_industry)",
  "output_policy":"INTERNAL_ONLY",
  "entity_scopes":["USER","ITEM"],
  "value_shape":"SEQUENCE"
}
```

Use this public sequence fixture to preserve the existing expected counts `3, 1, 3, 0`:

```java
private static List<String> publicIndustrySequence() {
    return List.of(
            "industry1", "industry2", "industry1",
            "industry3", "industry1", "industry3");
}
```

The low-level `ExampleFeatures` fusion tests continue using `SequenceBlock`; do not convert those internal tests to the public array protocol.

- [ ] **Step 8: Add an online shared-output boundary test**

Add a focused ONLINE public-API test with this config:

```json
{
  "feature_set":"shared-array-output",
  "version":"1",
  "features":[
    {"name":"scene_value","raw_name":"scene_value","type":"DOUBLE",
     "definition_type":"BASE","entity_scopes":["SCENE"],"value_shape":"SCALAR"},
    {"name":"scene_score","store_name":"scene_score","type":"DOUBLE",
     "definition_type":"DERIVED","expression":"multiply(scene_value, 2.0)",
     "output_policy":"OUTPUT","entity_scopes":["SCENE"],"value_shape":"SCALAR"}
  ]
}
```

Generate with `sharedValues = Map.of("scene_value", List.of(2.0))` and two empty candidate Maps.
Assert `featureValues().get("scene_score").equals(List.of(4.0))`, both candidate result Maps are empty,
and the physical stage assertion for `feature:scene_score` remains `REQUEST_SHARED`. This proves the
output does not acquire a candidate axis; existing plan/state tests establish that shared nodes occur once.

- [ ] **Step 9: Add exact nullable-boundary and defensive-copy coverage**

In `testConfigurationAndRequestValidation`, build an explicit-null scalar as:

```java
Map<String, List<?>> explicitNullRow = new LinkedHashMap<>();
explicitNullRow.put("raw_price", Collections.singletonList(null));
explicitNullRow.put("quality_score", List.of(0.8));
```

Add a small config whose scalar derived output is null and assert the public value is a one-element List containing null. Mutate an input `ArrayList` after request construction and assert the request retained the original value, proving the DTO copied the feature List.

Use this complete null-output config so the test is reproducible:

```json
{
  "feature_set":"nullable-array-output",
  "version":"1",
  "features":[
    {"name":"source","raw_name":"source","type":"STRING","definition_type":"BASE",
     "entity_scopes":["USER"],"value_shape":"SCALAR"},
    {"name":"nullable_out","store_name":"nullable_out","type":"STRING",
     "definition_type":"DERIVED","expression":"coalesce(source, null)",
     "output_policy":"OUTPUT","entity_scopes":["USER"],"value_shape":"SCALAR"}
  ]
}
```

Call it with `source = Collections.singletonList(null)` and assert the output has size one and contains
null. For `OfflineGenerateRequest`, mutate the caller's Map and inner `ArrayList` after construction. For
`OnlineGenerateRequest`, independently mutate the shared Map, candidate outer `ArrayList`, candidate
`LinkedHashMap`, and candidate value `ArrayList`; assert all request getters retain their original snapshots.

- [ ] **Step 10: Preserve fail-fast public behavior**

Migrate the existing missing-source and wrong-request-mode assertions to array inputs. Add a two-output
config where `good_out = coalesce(source, 0.0)` and
`bad_out = div_num(source, {"divisor":0})`, both declared as OUTPUT. Assert `generate` throws one
`FeatureGenerationException` containing `divisor must not be zero`; there is no `GenerateResult` and no
partial `good_out` Map to inspect. Retain the candidate-result cardinality guard in `FeatureDagEngine` and
the empty/one/four/reordered candidate assertions; do not add a test-only Runtime seam merely to fabricate
an otherwise unreachable vector-size mismatch.

```json
{
  "feature_set":"fail-fast-array-output",
  "version":"1",
  "features":[
    {"name":"source","raw_name":"source","type":"DOUBLE","definition_type":"BASE",
     "entity_scopes":["USER"],"value_shape":"SCALAR"},
    {"name":"good_out","store_name":"good_out","type":"DOUBLE",
     "definition_type":"DERIVED","expression":"coalesce(source, 0.0)",
     "output_policy":"OUTPUT","entity_scopes":["USER"],"value_shape":"SCALAR","order":1},
    {"name":"bad_out","store_name":"bad_out","type":"DOUBLE",
     "definition_type":"DERIVED","expression":"div_num(source, {\"divisor\":0})",
     "output_policy":"OUTPUT","entity_scopes":["USER"],"value_shape":"SCALAR","order":2}
  ]
}
```

- [ ] **Step 11: Run the complete self-test and commit the API migration**

Run:

```bash
./scripts/run-self-test.sh
```

Expected: `All DAG engine self tests passed.`

Also compile production and test sources:

```bash
mvn -q -DskipTests test-compile
```

Expected: exit code 0.

Commit:

```bash
git add src/main/java/com/example/featuredag/api \
  src/main/java/com/example/featuredag/runtime/CandidateVectorValue.java \
  src/main/java/com/example/featuredag/demo/DagDemo.java \
  src/test/java/com/example/featuredag/DagEngineSelfTest.java
git commit -m "Migrate generate API to array values"
```

---

### Task 3: Remove the global raw-sequence length restriction

**Files:**
- Modify: `src/main/java/com/example/featuredag/runtime/ExecutionContext.java:19-20,60-74`
- Modify: `src/main/java/com/example/featuredag/runtime/DagRuntime.java:222-237`
- Modify: `src/test/java/com/example/featuredag/DagEngineSelfTest.java:83-85,504-531`

**Interfaces:**
- Consumes: internal natural sequence Lists produced by Task 2 decoding.
- Produces: independent raw List sequences may have different lengths; operator-specific index/number checks remain unchanged.

- [ ] **Step 1: Replace the mismatch-rejection test with an allowed-independent-sequences test**

Rename `testMisalignedRawListSequenceLengthsFail` to `testIndependentRawListSequenceLengthsAreAllowed`, update the main invocation, and replace the exception assertion with successful execution:

```java
ExecutionResult result = new DagRuntime(registry).execute(
        plan,
        ExecutionContext.offlineRow(
                "independent-sequences",
                Map.of(
                        "apps", List.of("app0", "app1"),
                        "timestamps", List.of(20L))));
assert ((ListSequenceValue) result.feature("apps")).size() == 2;
assert ((ListSequenceValue) result.feature("timestamps")).size() == 1;
```

- [ ] **Step 2: Run the self-test to verify the old global check fails the new test**

Run:

```bash
./scripts/run-self-test.sh
```

Expected: FAIL with `Raw sequence length mismatch`.

- [ ] **Step 3: Remove execution-global sequence registration**

Delete `rawSequenceLength`, `firstRawSequenceFeature`, and `registerRawSequence` from `ExecutionContext`.

Simplify `DagRuntime.wrapSource` so ordinary sequence Lists are wrapped without registration:

```java
private static ValueHandle wrapSource(
        Object value,
        ValueShape logicalValueShape,
        ExecutionContext context) {
    if (value instanceof ValueHandle handle) return handle;
    if (logicalValueShape == ValueShape.SEQUENCE && value instanceof List<?> list) {
        return new ListSequenceValue(context.executionId(), list);
    }
    return wrap(value, logicalValueShape, context.executionId());
}
```

Remove the now-unused `featureName` argument from both `wrapSource` call sites. Keep `executionId` as the request-local `ListSequenceValue.alignmentId`; do not add alignment comparison logic.

- [ ] **Step 4: Run sequence and full regression verification**

Run:

```bash
./scripts/run-self-test.sh
```

Expected: `All DAG engine self tests passed.`

Commit:

```bash
git add src/main/java/com/example/featuredag/runtime/ExecutionContext.java \
  src/main/java/com/example/featuredag/runtime/DagRuntime.java \
  src/test/java/com/example/featuredag/DagEngineSelfTest.java
git commit -m "Allow independent raw sequence lengths"
```

---

### Task 4: Update the Demo, repository contracts, and full verification

**Files:**
- Modify: `src/main/java/com/example/featuredag/demo/DagDemo.java`
- Modify: `src/test/java/com/example/featuredag/DagEngineSelfTest.java:627-666`
- Modify: `README.md`
- Modify: `AGENTS.md`

**Interfaces:**
- Consumes: final array-only API from Task 2 and independent sequence behavior from Task 3.
- Produces: runnable/documented public contract with a fresh package, self-test, and Demo verification.

- [ ] **Step 1: Use element type plus shape in the three-day config**

In `DagDemo.CONFIG_JSON` and `testThreeDayAppCountFromAlignedLists`, replace the ordinary-list pseudo types:

```json
{"name":"auid_app_time_seq","raw_name":"auid_app_time_seq","type":"STRING",
 "definition_type":"BASE","entity_scopes":["USER"],"value_shape":"SEQUENCE"},
{"name":"timestamp","raw_name":"timestamp","type":"INT",
 "definition_type":"BASE","entity_scopes":["USER"],"value_shape":"SEQUENCE"}
```

Keep `EVENT_SEQUENCE` elsewhere for internal `SequenceBlock`/`SequenceView` scenarios.

- [ ] **Step 2: Finish the Demo output contract**

Ensure `DagDemo` uses:

```java
Map<String, List<?>> row = Map.of(
        "auid", List.of("aaaa"),
        "auid_app_time_seq", List.of("app0", "app1", "app2", "app3"),
        "timestamp", List.of(1785549653L, 1785459831L, 1785286488L, 1785203315L),
        "request_time", List.of(1785549653),
        "target_app", List.of("app0"));
```

Read scalar display values with `.getFirst()` and expect:

```text
FEATURES: {auid_omnichannel_paid_cnt_3d=[1]}
```

- [ ] **Step 3: Rewrite README public input/output examples**

Update every public request example to use array values:

```java
Map<String, List<?>> rowValues = Map.of(
        "raw_price", List.of(100.0),
        "quality_score", List.of(0.8));
Map<String, List<?>> features = engine.generate(
        new OfflineGenerateRequest("row-1", rowValues)).featureValues();
```

Document these exact rules:

- SCALAR is externally `[value]` and internally unwrapped using configured `value_shape`;
- SEQUENCE remains `[v1, v2, ...]`;
- candidate ITEM scalars are single-element Lists inside each candidate Map;
- outputs always use Lists, including scalar outputs;
- old `ratings=["1|0|1|v2"]` must become `ratings=[1,0,1]` before the engine;
- the DAG stage runs before `preTransform`/hash/model encoding;
- `featureValues()` merges into shared/user features, while
  `candidateFeatureValues().get(i)` merges into candidate `i` without reordering;
- merge-time name conflicts and `ExecutionSession` writes belong to the external
  `FeatureDagGenerateTask`, not this repository;
- v1 does not validate shape/type/alignment and does not require all raw sequences to be equal length;
- no partial outputs or Runtime fallback are returned on failure.

Remove the old statements that `auid` must be a bare String and that Runtime validates all raw Lists to one common length.

- [ ] **Step 4: Update `AGENTS.md` Demo contract**

Replace the current scalar and global alignment bullets with:

```text
- 公共 generate API 的所有输入值均为普通 Java List。
- SCALAR 使用单元素 List，例如 auid=["aaaa"]、request_time=[1785549653]。
- SEQUENCE 使用完整元素 List；第一版不执行跨序列 alignment 校验。
- 调用方负责在调用引擎前把 "1|0|1|v2" 等旧协议转换为干净数组。
- 三天计数公共输出为 auid_omnichannel_paid_cnt_3d=[1]。
```

Keep the strict timestamp boundary and required operator-chain requirements.

- [ ] **Step 5: Run fresh complete verification**

Run the assertion self-test:

```bash
./scripts/run-self-test.sh
```

Expected: `All DAG engine self tests passed.`

Build both thin and shaded JARs:

```bash
mvn clean package
```

Expected: exit code 0 and both of these files exist:

```text
target/feature-dag-engine-1.0.0-SNAPSHOT.jar
target/feature-dag-engine-1.0.0-SNAPSHOT-all.jar
```

Run the packaged Demo:

```bash
java -jar target/feature-dag-engine-1.0.0-SNAPSHOT-all.jar
```

Expected output includes:

```text
AUID: aaaa
TARGET_APP: app0
FEATURES: {auid_omnichannel_paid_cnt_3d=[1]}
```

Check formatting and the exact changed-file set:

```bash
git diff --check
git status --short
```

Expected: `git diff --check` exits 0; status contains only the intended Task 4 documentation/Demo/test changes before commit.

- [ ] **Step 6: Commit the completed contract**

```bash
git add src/main/java/com/example/featuredag/demo/DagDemo.java \
  src/test/java/com/example/featuredag/DagEngineSelfTest.java \
  README.md AGENTS.md
git commit -m "Document unified array feature API"
```

After the commit, rerun `git status --short` and require an empty result.

---

### Post-implementation discussion: partial output contract (no v1 code change)

After Task 4 is complete and verified, explain a possible next-version best-effort API without changing
the v1 fail-fast behavior. The discussion must distinguish:

- successful shared outputs and successful per-candidate outputs, which keep the same
  `Map<String, List<?>>` representation;
- output-level failures, which need structured metadata such as `featureName`, `storeName`, shared versus
  candidate location, optional `candidateIndex`, stable error code, message, and cause/node diagnostics;
- one failed shared dependency affecting every requested output that depends on it;
- one candidate-local failure versus failure of the complete candidate-vector output;
- explicit execution policy (`FAIL_FAST` versus a future `BEST_EFFORT`) so callers never infer success from
  a missing key;
- HTTP/RPC success semantics and monitoring remaining an integration-layer decision.

Recommend a separate result/error envelope or explicit best-effort entry point rather than silently adding
missing keys to the current `GenerateResult`. Do not implement this contract until its dependency-failure,
candidate-failure, and status semantics are separately approved.
