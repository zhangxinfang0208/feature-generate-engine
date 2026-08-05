# Public init/generate API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an immutable Java 21 `FeatureDagEngine` facade that initializes a DAG from the existing JSON feature-set format and generates ordinary Java values for both Spark/Scala offline rows and concurrent online Java requests.

**Architecture:** Jackson-backed configuration DTOs map the business JSON into the existing compact `FeatureDefinition` model plus separate ordered output descriptors. `FeatureDagEngine.init` owns parse/build/optimize/plan work; `generate` creates an isolated execution context, delegates to the existing runtime, and materializes internal handles into Java collections. The artifact remains Spark-neutral and ships both thin and Jackson-relocated shaded JARs.

**Tech Stack:** Java 21, Maven, Jackson Databind 2.21.3 (2.x LTS line), Maven Shade Plugin 3.6.2, dependency-free Java `assert` self-tests.

## Global Constraints

- Compile and run on Java 21.
- Do not add Spark or Scala dependencies.
- Keep `FeatureDefinition` and DAG nodes free of business metadata and Jackson types.
- Public API values use only JDK types and existing public enums.
- Spark initializes one engine per partition; do not make or advertise the engine as serializable.
- Online calls share an immutable engine but create a new `ExecutionContext` per request.
- Unknown business JSON fields are accepted; known DAG fields are strictly validated.
- `derivedFeatures[].dft` is parsed as metadata but never masks execution failures.
- Use four-space indentation, explicit imports, and one public top-level type per Java file.
- The workspace has no `.git` directory, so the commit steps normally required by this skill are unavailable; record verification checkpoints instead and do not initialize a repository without user authorization.

---

## File Map

**Create public API files**

- `src/main/java/com/example/featuredag/api/FeatureDagEngine.java`: immutable facade and init/generate orchestration.
- `src/main/java/com/example/featuredag/api/InitOptions.java`: environment, plan ID, targets, and raw scope overrides.
- `src/main/java/com/example/featuredag/api/GenerateRequest.java`: common request contract.
- `src/main/java/com/example/featuredag/api/OfflineGenerateRequest.java`: one offline row.
- `src/main/java/com/example/featuredag/api/OnlineGenerateRequest.java`: online shared values and candidates.
- `src/main/java/com/example/featuredag/api/GenerateResult.java`: external scalar and per-candidate results.
- `src/main/java/com/example/featuredag/api/FeatureDagInitializationException.java`: init failure context.
- `src/main/java/com/example/featuredag/api/FeatureGenerationException.java`: execution failure context.

**Create configuration files**

- `src/main/java/com/example/featuredag/config/FeatureSetConfig.java`: top-level JSON DTO and extension fields.
- `src/main/java/com/example/featuredag/config/RawFeatureConfig.java`: raw JSON DTO.
- `src/main/java/com/example/featuredag/config/DerivedFeatureConfig.java`: derived JSON DTO.
- `src/main/java/com/example/featuredag/config/FlexibleBooleanDeserializer.java`: boolean or string-boolean compatibility.
- `src/main/java/com/example/featuredag/config/FeatureConfigLoader.java`: strict known-field value parsing from text/path.
- `src/main/java/com/example/featuredag/config/FeatureConfigMapper.java`: validation, filtering, target selection, scope/default conversion.
- `src/main/java/com/example/featuredag/config/FeatureOutputDescriptor.java`: logical name, store name, order, and declaration index.
- `src/main/java/com/example/featuredag/config/MappedFeatureSet.java`: immutable mapped definitions, targets, and outputs.

**Create runtime boundary file**

- `src/main/java/com/example/featuredag/runtime/ExternalValueMaterializer.java`: recursive scalar/sequence materialization.

**Modify existing files**

- `src/test/java/com/example/featuredag/DagEngineSelfTest.java`: all red/green behavior coverage.
- `src/main/java/com/example/featuredag/runtime/DagRuntime.java`: distinguish absent source keys from explicitly present null values in offline and online inputs.
- `src/main/java/com/example/featuredag/demo/DagDemo.java`: demonstrate public API instead of manual internal assembly.
- `pom.xml`: Jackson, Shade, dependency classpath support.
- `scripts/run-demo.sh`: Maven-aware demo execution.
- `scripts/run-self-test.sh`: Maven-aware assertion test execution.
- `README.md`: JSON, Java online, and Scala/Spark examples.

---

### Task 1: Parse the Existing Business JSON Shape

**Files:**

- Modify: `pom.xml`
- Create: `src/main/java/com/example/featuredag/config/FeatureSetConfig.java`
- Create: `src/main/java/com/example/featuredag/config/RawFeatureConfig.java`
- Create: `src/main/java/com/example/featuredag/config/DerivedFeatureConfig.java`
- Create: `src/main/java/com/example/featuredag/config/FlexibleBooleanDeserializer.java`
- Create: `src/main/java/com/example/featuredag/config/FeatureConfigLoader.java`
- Test: `src/test/java/com/example/featuredag/DagEngineSelfTest.java`

**Interfaces:**

- Produces: `FeatureConfigLoader.load(String): FeatureSetConfig`
- Produces: `FeatureConfigLoader.load(Path): FeatureSetConfig`
- Produces DTO accessors for `features`, `derivedFeatures`, `feature_set_name`, `version`, and all DAG-relevant child fields.

- [ ] **Step 1: Add a failing JSON compatibility test**

Add a `testBusinessJsonParsing()` call at the beginning of `main`, then add this method. The production change that makes it pass is a real loader that accepts existing business fields and mixed boolean representations.

```java
private static void testBusinessJsonParsing() {
    String json = """
            {
              "features": [{
                "catalog": "/mock/dir",
                "name": "price_type1",
                "raw_name": "price_type",
                "store_name": "price_type1",
                "type": "STRING",
                "feature_type": "sparse",
                "dft": "missing",
                "to_use": true,
                "order": 3,
                "is_feedback": "true",
                "entity_scopes": ["ITEM"],
                "future_business_field": "kept"
              }],
              "derivedFeatures": [{
                "name": "price_present",
                "store_name": "price_present_out",
                "type": "STRING",
                "expression": "coalesce(price_type1, \"missing\")",
                "to_use": true,
                "output_policy": "OUTPUT",
                "order": 10
              }],
              "feature_set_name": " test_001 ",
              "version": "latest"
            }
            """;

    FeatureSetConfig config = FeatureConfigLoader.load(json);
    assert config.features().size() == 1 : config.features();
    RawFeatureConfig raw = config.features().getFirst();
    assert raw.name().equals("price_type1") : raw.name();
    assert raw.rawName().equals("price_type") : raw.rawName();
    assert Boolean.TRUE.equals(raw.isFeedback()) : raw.isFeedback();
    assert raw.additionalProperties().get("future_business_field").equals("kept")
            : raw.additionalProperties();
    assert config.derivedFeatures().getFirst().outputPolicy().equals("OUTPUT");
    assert config.featureSetName().equals(" test_001 ") : config.featureSetName();
}
```

- [ ] **Step 2: Run the self-test compile and verify RED**

Run:

```powershell
mvn -q -DskipTests test-compile
```

Expected: compilation fails because `FeatureSetConfig`, `RawFeatureConfig`, and `FeatureConfigLoader` do not exist.

- [ ] **Step 3: Add Jackson and implement immutable DTO loading**

Add to `pom.xml`:

```xml
<dependencies>
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <version>2.21.3</version>
    </dependency>
</dependencies>
```

Implement DTO constructors with `@JsonCreator`/`@JsonProperty`, defensive `List.copyOf`, and `@JsonAnySetter`-backed extension maps. Bind snake-case JSON names explicitly rather than globally changing naming rules. Use this loader shape:

```java
public final class FeatureConfigLoader {
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public static FeatureSetConfig load(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Feature config JSON must not be blank");
        }
        try {
            return OBJECT_MAPPER.readValue(json, FeatureSetConfig.class);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Invalid feature config JSON: " + error.getOriginalMessage(), error);
        }
    }

    public static FeatureSetConfig load(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            return load(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new IllegalArgumentException("Failed to read feature config: " + path, error);
        }
    }
}
```

`FlexibleBooleanDeserializer` must accept JSON booleans and the strings `true`/`false` case-insensitively, return null for JSON null, and reject all other tokens/strings with a mapping error.

- [ ] **Step 4: Run the compatibility test and verify GREEN**

Run the Maven classpath setup and assertion main:

```powershell
mvn -q -DskipTests test-compile dependency:build-classpath "-Dmdep.outputFile=target/test-classpath.txt"
$testCp = "target/test-classes;target/classes;" + (Get-Content -Raw target/test-classpath.txt)
java -ea -cp $testCp com.example.featuredag.DagEngineSelfTest
```

Expected: the new parsing assertion passes; existing DAG assertions also pass.

- [ ] **Step 5: Verification checkpoint**

Record `mvn -q -DskipTests test-compile` exit code 0. No Git commit is possible because the workspace has no `.git` directory.

---

### Task 2: Map Config to Definitions, Targets, and Intermediate Dependencies

**Files:**

- Create: `src/main/java/com/example/featuredag/config/FeatureOutputDescriptor.java`
- Create: `src/main/java/com/example/featuredag/config/MappedFeatureSet.java`
- Create: `src/main/java/com/example/featuredag/config/FeatureConfigMapper.java`
- Test: `src/test/java/com/example/featuredag/DagEngineSelfTest.java`

**Interfaces:**

- Consumes: `FeatureSetConfig`, `InitOptions` values expressed initially as explicit mapper arguments.
- Produces: `FeatureConfigMapper.map(FeatureSetConfig, ExecutionEnvironment, Set<String>, Map<String, Set<EntityScope>>): MappedFeatureSet`
- Produces: `MappedFeatureSet.definitions(): List<FeatureDefinition>`
- Produces: `MappedFeatureSet.targetFeatures(): Set<String>` in output order.
- Produces: `MappedFeatureSet.outputs(): List<FeatureOutputDescriptor>` for selected roots only.

- [ ] **Step 1: Add a failing intermediate-feature mapping test**

Add `testIntermediateFeatureMapping()` to `main` and implement:

```java
private static void testIntermediateFeatureMapping() {
    FeatureSetConfig config = FeatureConfigLoader.load(intermediateConfigJson());
    MappedFeatureSet mapped = FeatureConfigMapper.map(
            config,
            ExecutionEnvironment.OFFLINE,
            Set.of(),
            Map.of());

    assert mapped.targetFeatures().equals(new LinkedHashSet<>(List.of("price_score")))
            : mapped.targetFeatures();
    assert mapped.outputs().size() == 1 : mapped.outputs();
    assert mapped.outputs().getFirst().storeName().equals("price_score_out");
    assert mapped.definitions().stream().anyMatch(definition ->
            definition.name().equals("normalized_price")
                    && definition.outputPolicy() == OutputPolicy.INTERNAL_ONLY)
            : "Intermediate definition must remain available to the dependency closure";

    LogicalDag dag = new LogicalDagBuilder(
            new ExpressionParser(), OperatorRegistry.standard())
            .build(mapped.definitions(), mapped.targetFeatures());
    assert dag.featureOutputNodeIds().containsKey("normalized_price")
            : "Final target must recursively build its intermediate dependency";
    assert !dag.rootNodeIds().contains(dag.featureOutputNodeIds().get("normalized_price"))
            : "Intermediate dependency must not become an output root";
}
```

The fixture must declare raw `price`, raw `quality_score`, internal `normalized_price`, and output `price_score` with expression `multiply(normalized_price, quality_score)` and `store_name` `price_score_out`.

- [ ] **Step 2: Run and verify RED**

Run `mvn -q -DskipTests test-compile`.

Expected: compilation fails because mapper/output types do not exist.

- [ ] **Step 3: Implement mapping and exact validation**

Implement immutable output records/classes with these contracts:

```java
public record FeatureOutputDescriptor(
        String featureName,
        String storeName,
        int order,
        int declarationIndex) {}

public record MappedFeatureSet(
        String featureSetName,
        String version,
        List<FeatureDefinition> definitions,
        Set<String> targetFeatures,
        List<FeatureOutputDescriptor> outputs,
        Set<String> unresolvedOnlineScopes) {}
```

`FeatureConfigMapper.map` must:

1. Trim and require `feature_set_name` and `version`.
2. Combine raw and derived names into one duplicate check.
3. Exclude `to_use=false` definitions, while retaining disabled names to produce a specific disabled-dependency error when referenced.
4. Map `raw_name` to `sourceBinding`, `type` to `DataType`, `dft` to a type-checked Java value, and final scope from option override then JSON.
5. Default an absent offline scope to `USER`. For online mapping, use `USER` only as an internal construction placeholder and record the logical name in `unresolvedOnlineScopes`; Task 5 rejects an unresolved feature when it is present in the actual target DAG before physical planning.
6. Map every enabled derived feature to `FeatureRole.DERIVED`, expression, declared type, output policy, default metadata, and description.
7. Select explicit targets when nonempty; otherwise select enabled `OUTPUT` derived features ordered by `(order, declarationIndex)`.
8. Reject raw, missing, disabled, or `INTERNAL_ONLY` explicit targets.
9. Require unique `store_name` among selected targets.
10. Return selected descriptors ordered by `(order, declarationIndex)` and a `LinkedHashSet` of target logical names in that same order.

Do not parse expression dependencies a second time inside the mapper. Let `LogicalDagBuilder` remain the source of truth for dependency resolution and translate its missing-definition error into an initialization error in Task 3.

- [ ] **Step 4: Verify GREEN**

Run the complete assertion main using the classpath command from Task 1.

Expected: parsing, mapping, intermediate-closure, and existing engine assertions pass.

- [ ] **Step 5: Verification checkpoint**

Record successful assertion-main output. No Git commit is possible in this workspace.

---

### Task 3: Add Immutable Public DTOs and Offline init/generate

**Files:**

- Create: `src/main/java/com/example/featuredag/api/InitOptions.java`
- Create: `src/main/java/com/example/featuredag/api/GenerateRequest.java`
- Create: `src/main/java/com/example/featuredag/api/OfflineGenerateRequest.java`
- Create: `src/main/java/com/example/featuredag/api/OnlineGenerateRequest.java`
- Create: `src/main/java/com/example/featuredag/api/GenerateResult.java`
- Create: `src/main/java/com/example/featuredag/api/FeatureDagInitializationException.java`
- Create: `src/main/java/com/example/featuredag/api/FeatureGenerationException.java`
- Create: `src/main/java/com/example/featuredag/runtime/ExternalValueMaterializer.java`
- Create: `src/main/java/com/example/featuredag/api/FeatureDagEngine.java`
- Modify: `src/main/java/com/example/featuredag/runtime/DagRuntime.java`
- Test: `src/test/java/com/example/featuredag/DagEngineSelfTest.java`

**Interfaces:**

- Produces: `FeatureDagEngine.init(Path, InitOptions)` and `FeatureDagEngine.init(String, InitOptions)`.
- Produces: `FeatureDagEngine.generate(GenerateRequest): GenerateResult`.
- Produces request/result contracts exactly as specified in the approved design.

- [ ] **Step 1: Add a failing offline public-API test**

Add `testOfflinePublicApi()` to `main`:

```java
private static void testOfflinePublicApi() {
    FeatureDagEngine engine = FeatureDagEngine.init(
            intermediateConfigJson(),
            InitOptions.offline("offline-public-api"));

    Map<String, Object> row = new LinkedHashMap<>();
    row.put("raw_price", 100.0);
    row.put("quality_score", 0.8);
    GenerateResult result = engine.generate(
            new OfflineGenerateRequest("row-1", row));

    assert result.executionId().equals("row-1");
    assert result.featureValues().keySet().equals(Set.of("price_score_out"))
            : result.featureValues();
    assert Math.abs(((Number) result.featureValues().get("price_score_out")).doubleValue() - 0.08) < 0.000001
            : result.featureValues();
    assert result.candidateFeatureValues().isEmpty();
    assert !result.featureValues().containsKey("normalized_price")
            : "Internal feature leaked through the public boundary";
}
```

Use a normalization max of `1000`, so normalized price is `0.1` and final score is `0.08`.

In the same RED step, add `testConfigPathInit()` using `Files.createTempFile`, write `intermediateConfigJson()` as UTF-8, initialize through the `Path` overload, assert the same `price_score_out=0.08`, and delete that exact temporary file in `finally`.

- [ ] **Step 2: Run and verify RED**

Run `mvn -q -DskipTests test-compile`.

Expected: compilation fails because the API package types do not exist.

- [ ] **Step 3: Implement immutable options and request/result DTOs**

`InitOptions` must expose:

```java
public static Builder builder();
public static InitOptions offline(String planId);
public static InitOptions online(String planId);
public ExecutionEnvironment environment();
public String planId();
public Set<String> targetFeatures();
public Map<String, Set<EntityScope>> rawFeatureScopes();
```

The Builder must have `environment`, `planId`, `targetFeatures`, and `rawFeatureScopes` setters. All collections use nested defensive copies and unmodifiable wrappers. Request and result constructors must likewise defensively copy maps, lists, and candidate maps.

- [ ] **Step 4: Implement external value materialization**

Use this behavioral outline:

```java
public final class ExternalValueMaterializer {
    public Object materialize(ValueHandle handle) {
        Objects.requireNonNull(handle, "handle");
        if (handle instanceof ScalarValue scalar) return materializeRaw(scalar.value());
        if (handle instanceof SequenceValue sequence) return materializeSequence(sequence);
        if (handle instanceof CandidateVectorValue vector) {
            return vector.values().stream().map(this::materializeRaw).toList();
        }
        throw new IllegalArgumentException("Unsupported public output handle: " + handle.getClass().getName());
    }
}
```

Sequence materialization iterates logical indices, reads `SequenceEvent` from the base block, and produces ordered maps with exactly `itemId`, `industryId`, `timestamp`, `eventType`, and `value`. Recursively copy input maps/lists; pass immutable scalar values through.

- [ ] **Step 5: Implement FeatureDagEngine init and offline generate**

`init` performs loader → mapper → builder → optimizer → planner exactly once. Derive an absent plan ID as `<trimmed-feature_set_name>-<trimmed-version>-<lowercase-environment>`. Store the plan, runtime, environment, feature metadata, and selected output descriptors in final fields.

`generate` validates request type against environment. For `OfflineGenerateRequest`, create `ExecutionContext.offlineRow`, execute the plan, look up every descriptor by logical feature name, materialize it, and insert it under `storeName` in descriptor order. Return an empty candidate list.

Update `DagRuntime.executeSource` so source lookup first checks `containsKey(sourceBinding)`. A missing key uses a non-null default or throws; an explicitly present null is passed to the operator layer. Apply the same rule independently to every online candidate rather than allowing a missing required candidate field to become a null vector entry.

Wrap all initialization failures in `FeatureDagInitializationException` with feature set/version/plan context where available. Wrap execution failures in `FeatureGenerationException` with plan and execution ID while preserving the cause.

- [ ] **Step 6: Verify GREEN**

Run the full assertion main.

Expected: offline public API returns only `price_score_out=0.08`; all prior tests remain green.

- [ ] **Step 7: Verification checkpoint**

Record full self-test output and inspect the public DTOs for defensive copies. No Git commit is possible.

---

### Task 4: Implement Online Candidate Results and Thread-Safe Reuse

**Files:**

- Modify: `src/main/java/com/example/featuredag/api/FeatureDagEngine.java`
- Modify: `src/main/java/com/example/featuredag/runtime/ExternalValueMaterializer.java`
- Test: `src/test/java/com/example/featuredag/DagEngineSelfTest.java`

**Interfaces:**

- Consumes: `OnlineGenerateRequest` and an engine initialized with `ExecutionEnvironment.ONLINE`.
- Produces: scalar request results in `GenerateResult.featureValues()` and transposed candidate results in `candidateFeatureValues()`.

- [ ] **Step 1: Add failing online and concurrent-reuse public-API tests**

Create an online JSON fixture using the existing `user_click_count`, `user_seq1`, `item_industry`, `item_price`, `same_industry_seq`, `same_industry_count`, `item_price_log`, and `final_score` expressions. Mark the two final targets `OUTPUT`, intermediate definitions `INTERNAL_ONLY`, user sources `USER`, and item sources `ITEM`.

Add:

```java
private static void testOnlinePublicApi() {
    FeatureDagEngine engine = FeatureDagEngine.init(
            onlineConfigJson(),
            InitOptions.online("online-public-api"));
    GenerateResult result = engine.generate(new OnlineGenerateRequest(
            "request-1",
            Map.of("user_click_count", 10, "user_seq1", sequence()),
            List.of(
                    Map.of("item_industry", "industry1", "item_price", 100.0),
                    Map.of("item_industry", "industry2", "item_price", 50.0),
                    Map.of("item_industry", "industry1", "item_price", 80.0))));

    assert result.featureValues().isEmpty() : result.featureValues();
    assert result.candidateFeatureValues().size() == 3;
    assert result.candidateFeatureValues().stream()
            .map(values -> values.get("same_industry_count"))
            .toList().equals(List.of(3, 1, 3)) : result.candidateFeatureValues();
    assert result.candidateFeatureValues().stream()
            .allMatch(values -> values.containsKey("final_score"));
}
```

Also add the concurrent test below in the same RED step, before any online implementation:

```java
private static void testOnlineEngineConcurrentReuse() throws Exception {
    FeatureDagEngine engine = FeatureDagEngine.init(
            onlineConfigJson(), InitOptions.online("online-concurrent"));
    try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
        List<Callable<List<Object>>> calls = IntStream.range(0, 20)
                .mapToObj(index -> (Callable<List<Object>>) () -> {
                    GenerateResult result = engine.generate(new OnlineGenerateRequest(
                            "request-" + index,
                            Map.of("user_click_count", 10, "user_seq1", sequence()),
                            List.of(
                                    Map.of("item_industry", "industry1", "item_price", 100.0),
                                    Map.of("item_industry", "industry2", "item_price", 50.0))));
                    return result.candidateFeatureValues().stream()
                            .map(values -> values.get("same_industry_count"))
                            .toList();
                })
                .toList();
        for (Future<List<Object>> future : executor.invokeAll(calls)) {
            assert future.get().equals(List.of(3, 1)) : future.get();
        }
    }
}
```

- [ ] **Step 2: Run and verify RED**

Run the full assertion main.

Expected: both tests fail because online requests/candidate result transposition are not implemented.

- [ ] **Step 3: Implement online result partitioning and transposition**

Execute with `ExecutionContext.onlineRequest`. For each selected descriptor:

- `CandidateVectorValue`: require vector size equals request candidate count, materialize each entry, and insert under the descriptor store name in the corresponding candidate map.
- Scalar or sequence handle: materialize once into `featureValues`.
- Build every candidate map even when no candidate target exists, preserving candidate count and order.

Return deeply immutable result collections.

- [ ] **Step 4: Verify online GREEN**

Run the full assertion main.

Expected: candidate count values are `[3, 1, 3]`, all candidates contain `final_score`, and existing direct-runtime optimization assertions remain green.

- [ ] **Step 5: Verify concurrent isolation and mutation coverage**

The mutation caught by the concurrent test is moving `ExecutionContext`, result slots, caches, or node states into shared engine fields. Inspect `FeatureDagEngine` final fields and confirm none are per-request mutable structures.

- [ ] **Step 6: Verification checkpoint**

Run the assertion main three consecutive times to detect obvious shared-state flakiness. All runs must exit 0.

---

### Task 5: Complete Validation and Contextual Exceptions

**Files:**

- Modify: `src/main/java/com/example/featuredag/config/FeatureConfigMapper.java`
- Modify: `src/main/java/com/example/featuredag/api/FeatureDagEngine.java`
- Modify: `src/main/java/com/example/featuredag/api/FeatureDagInitializationException.java`
- Modify: `src/main/java/com/example/featuredag/api/FeatureGenerationException.java`
- Test: `src/test/java/com/example/featuredag/DagEngineSelfTest.java`

**Interfaces:**

- Produces stable initialization exception context accessors: `featureSetName()`, `version()`, `planId()`, `featureName()`.
- Produces generation exception accessors: `planId()`, `executionId()`, `featureName()`.

- [ ] **Step 1: Add table-driven failing validation tests**

Add a helper that runs an action and returns the expected exception without using JUnit:

```java
private static <T extends Throwable> T expectThrows(Class<T> type, Runnable action) {
    try {
        action.run();
    } catch (Throwable error) {
        if (type.isInstance(error)) return type.cast(error);
        throw new AssertionError("Expected " + type.getName() + " but got " + error, error);
    }
    throw new AssertionError("Expected " + type.getName() + " but nothing was thrown");
}
```

Add independent assertions for these literal fixtures:

- Duplicate raw/derived `name` → initialization exception contains the duplicate name.
- Two selected outputs with the same `store_name` → initialization exception contains the store name.
- Explicit target points at `INTERNAL_ONLY` → initialization exception names the target.
- Output B references `to_use=false` A → initialization exception names A and says disabled.
- A references B and B references A → initialization exception includes both names and `cycle`.
- Online source in the selected closure has no JSON scope and no override → initialization exception names the raw feature.
- JSON scope is absent but `InitOptions.rawFeatureScopes` supplies ITEM → init succeeds.
- Offline engine receives `OnlineGenerateRequest` → generation exception includes plan and execution IDs.
- Required `raw_name` is absent and `dft` is null/absent → generation exception includes the source binding.
- Required `raw_name` is explicitly present with null → source lookup succeeds and the consuming operator, rather than the binding layer, determines whether null is valid.

- [ ] **Step 2: Run and verify RED**

Run the full assertion main.

Expected: at least the disabled-dependency message, closure-only online scope validation, or contextual exception properties fail.

- [ ] **Step 3: Implement closure-aware validation**

After DAG creation, derive the source features actually present in the logical DAG. In online mode, intersect those logical names with `MappedFeatureSet.unresolvedOnlineScopes()` and reject every intersection before physical planning. To distinguish disabled references, pre-scan every enabled derived expression with the existing `ExpressionParser`, recursively collect `AstFeatureRef` names, and report a direct reference to a known disabled name as disabled before DAG build. Do not use this scan to construct dependency edges; `LogicalDagBuilder` remains authoritative.

- [ ] **Step 4: Implement contextual exception properties**

Exception constructors store nullable context fields and format one stable message prefix:

```text
Failed to initialize feature DAG [featureSet=..., version=..., planId=..., feature=...]: ...
Failed to generate features [planId=..., executionId=..., feature=...]: ...
```

Do not discard the underlying parse/build/runtime cause. During output materialization, catch per-descriptor failures and attach that descriptor's logical feature name.

- [ ] **Step 5: Verify GREEN and mutation coverage**

Run the complete assertion main. Mentally mutate each branch: remove disabled check, accept duplicate store name, ignore request mode, or default missing input to null. Confirm a named assertion would fail for each mutation.

- [ ] **Step 6: Verification checkpoint**

Record the full passing assertion output. No Git commit is possible.

---

### Task 6: Package, Demonstrate, Document, and Verify the Distribution

**Files:**

- Modify: `pom.xml`
- Modify: `src/main/java/com/example/featuredag/demo/DagDemo.java`
- Modify: `scripts/run-demo.sh`
- Modify: `scripts/run-self-test.sh`
- Modify: `README.md`
- Test: `src/test/java/com/example/featuredag/DagEngineSelfTest.java`

**Interfaces:**

- Produces thin JAR: `target/feature-dag-engine-1.0.0-SNAPSHOT.jar`.
- Produces self-contained JAR: `target/feature-dag-engine-1.0.0-SNAPSHOT-all.jar`.
- Keeps main class `com.example.featuredag.demo.DagDemo`.

- [ ] **Step 1: Add shaded artifact expectations and verify RED**

Run:

```powershell
mvn clean package
Get-Item target/feature-dag-engine-1.0.0-SNAPSHOT.jar
Get-Item target/feature-dag-engine-1.0.0-SNAPSHOT-all.jar
```

Expected before Shade configuration: the thin JAR exists and the `-all.jar` lookup fails.

- [ ] **Step 2: Configure the shaded artifact with Jackson relocation**

Add Maven Shade Plugin 3.6.2 at `package` with:

```xml
<configuration>
    <shadedArtifactAttached>true</shadedArtifactAttached>
    <shadedClassifierName>all</shadedClassifierName>
    <createDependencyReducedPom>false</createDependencyReducedPom>
    <relocations>
        <relocation>
            <pattern>com.fasterxml.jackson</pattern>
            <shadedPattern>com.example.featuredag.internal.jackson</shadedPattern>
        </relocation>
    </relocations>
    <transformers>
        <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
            <mainClass>com.example.featuredag.demo.DagDemo</mainClass>
        </transformer>
    </transformers>
</configuration>
```

Do not minimize the shaded JAR because Jackson uses reflection.

- [ ] **Step 3: Rewrite the Demo through public init/generate**

Make `DagDemo` load an embedded text-block configuration, initialize one offline and one online engine, call public requests, and print only public `GenerateResult` maps. Do not manually instantiate `LogicalDagBuilder`, `PhysicalPlanner`, `DagRuntime`, or `ExecutionContext` in the demo.

- [ ] **Step 4: Update scripts for Maven dependencies**

`run-demo.sh` must execute:

```bash
mvn -q -DskipTests package
java -jar target/feature-dag-engine-1.0.0-SNAPSHOT-all.jar
```

`run-self-test.sh` must execute:

```bash
mvn -q -DskipTests test-compile dependency:build-classpath \
  -Dmdep.outputFile=target/test-classpath.txt
CP="target/test-classes:target/classes:$(cat target/test-classpath.txt)"
java -ea -cp "$CP" com.example.featuredag.DagEngineSelfTest
```

- [ ] **Step 5: Update README with exact integration examples**

Document:

- The accepted `features`/`derivedFeatures` JSON and intermediate `INTERNAL_ONLY` example.
- `name` vs `raw_name` vs `store_name`.
- Online scope requirements and option overrides.
- Java `init`/`generate` examples.
- Scala `mapPartitions` example that broadcasts JSON text and initializes per partition.
- Thin vs `-all` shaded artifact usage.
- Explicit statement that Java 21 is required on Spark executors and online JVMs.

- [ ] **Step 6: Run fresh full verification**

Run:

```powershell
mvn clean package
mvn -q -DskipTests test-compile dependency:build-classpath "-Dmdep.outputFile=target/test-classpath.txt"
$testCp = "target/test-classes;target/classes;" + (Get-Content -Raw target/test-classpath.txt)
java -ea -cp $testCp com.example.featuredag.DagEngineSelfTest
java -jar target/feature-dag-engine-1.0.0-SNAPSHOT-all.jar
jar tf target/feature-dag-engine-1.0.0-SNAPSHOT-all.jar | Select-String 'com/example/featuredag/internal/jackson/databind/ObjectMapper.class'
```

Expected:

- `mvn clean package` exits 0.
- Self-test prints `All DAG engine self tests passed.` and exits 0.
- Demo prints offline and online public results and exits 0.
- The final `jar tf` check finds relocated Jackson classes.
- Both thin and `-all` artifacts exist.

- [ ] **Step 7: Final requirements audit**

Re-read `docs/superpowers/specs/2026-08-05-public-init-generate-api-design.md` and check every completion criterion against code or fresh command output. Report any unmet criterion rather than claiming completion. No Git commit can be created because `.git` is absent.
