# Unified Feature Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Load, validate, plan, and execute base and derived feature declarations from one top-level `features` array.

**Architecture:** Replace the two configuration DTOs with one neutral `FeatureConfig`, then make `FeatureConfigMapper` normalize each declaration to BASE or DERIVED and construct the existing `FeatureDefinition` model. Extend `FeatureDefinition` with an optional declared logical shape so source nodes can honor BASE declarations and derived output nodes can validate configured shape and scopes against operator inference.

**Tech Stack:** Java 21, Jackson Databind 2.21.3, Maven, dependency-free Java assertions.

## Global Constraints

- Only the unified top-level `features` format is supported; a top-level `derivedFeatures` property must fail initialization.
- `definition_type` accepts `BASE` and `DERIVED`; null, blank, or absent means `BASE`.
- `entity_scopes` accepts only `ITEM`, `USER`, and `SCENE`.
- `value_shape` accepts only `SCALAR`, `SEQUENCE`, and `VECTOR`; `VECTOR` maps to logical `CANDIDATE_VECTOR`.
- `output_policy` accepts only `OUTPUT` and `INTERNAL_ONLY`; a missing derived value defaults to `OUTPUT`.
- Java 21 or newer is required and no runtime dependency beyond the existing shaded Jackson dependency may be added.

---

## File Structure

- Create `src/main/java/com/example/featuredag/config/FeatureConfig.java`: unified Jackson DTO and unknown-field retention.
- Create `src/main/java/com/example/featuredag/config/DefinitionType.java`: normalized BASE/DERIVED enum.
- Modify `src/main/java/com/example/featuredag/config/FeatureSetConfig.java`: expose only `List<FeatureConfig>`.
- Modify `src/main/java/com/example/featuredag/config/FeatureConfigLoader.java`: reject obsolete top-level `derivedFeatures` after deserialization.
- Modify `src/main/java/com/example/featuredag/config/FeatureConfigMapper.java`: one-pass normalization, definition construction, validation, target selection, and output ordering.
- Delete `src/main/java/com/example/featuredag/config/RawFeatureConfig.java`: superseded by `FeatureConfig`.
- Delete `src/main/java/com/example/featuredag/config/DerivedFeatureConfig.java`: superseded by `FeatureConfig`.
- Modify `src/main/java/com/example/featuredag/definition/FeatureDefinition.java`: retain optional declared `ValueShape`.
- Modify `src/main/java/com/example/featuredag/logical/LogicalDagBuilder.java`: apply source shape declarations and validate derived shape/scope constraints.
- Modify `src/test/java/com/example/featuredag/DagEngineSelfTest.java`: unified-format regression and acceptance coverage.
- Modify `src/main/java/com/example/featuredag/demo/DagDemo.java`: runnable unified JSON example.
- Modify `README.md`: document only the unified JSON contract.

### Task 1: Unified Configuration DTO and Mapper

**Files:**
- Create: `src/main/java/com/example/featuredag/config/FeatureConfig.java`
- Create: `src/main/java/com/example/featuredag/config/DefinitionType.java`
- Modify: `src/main/java/com/example/featuredag/config/FeatureSetConfig.java`
- Modify: `src/main/java/com/example/featuredag/config/FeatureConfigLoader.java`
- Modify: `src/main/java/com/example/featuredag/config/FeatureConfigMapper.java`
- Delete: `src/main/java/com/example/featuredag/config/RawFeatureConfig.java`
- Delete: `src/main/java/com/example/featuredag/config/DerivedFeatureConfig.java`
- Test: `src/test/java/com/example/featuredag/DagEngineSelfTest.java`

**Interfaces:**
- Consumes: JSON text passed to `FeatureConfigLoader.load(String)` and existing `FeatureConfigMapper.map(FeatureSetConfig, ExecutionEnvironment, Set<String>, Map<String, Set<EntityScope>>)`.
- Produces: `FeatureSetConfig.features(): List<FeatureConfig>` and unified definitions/targets/outputs in `MappedFeatureSet`.

- [ ] **Step 1: Add failing parsing and rejection tests**

Replace `RawFeatureConfig` imports with the wished-for `FeatureConfig` API, register both tests in `main`, and add assertions equivalent to:

```java
private static void testUnifiedFeatureJsonParsing() {
    FeatureSetConfig config = FeatureConfigLoader.load("""
            {
              "features": [
                {"name":"price","raw_name":"raw_price","type":"DOUBLE",
                 "definition_type":null,"value_shape":"SCALAR","future_business_field":"kept"},
                {"name":"price_score","type":"DOUBLE","definition_type":"DERIVED",
                 "expression":"multiply(price, price)","output_policy":"OUTPUT"}
              ],
              "feature_set_name":"test_001","version":"latest"
            }
            """);
    assert config.features().size() == 2 : config.features();
    FeatureConfig base = config.features().getFirst();
    assert base.definitionType() == null : base.definitionType();
    assert base.valueShape().equals("SCALAR") : base.valueShape();
    assert base.additionalProperties().get("future_business_field").equals("kept");
    assert config.features().get(1).expression().equals("multiply(price, price)");
}

private static void testLegacyDerivedFeaturesRejected() {
    IllegalArgumentException error = expectThrows(
            IllegalArgumentException.class,
            () -> FeatureConfigLoader.load("""
                    {"features":[],"derivedFeatures":[],
                     "feature_set_name":"legacy","version":"latest"}
                    """));
    assert error.getMessage().contains("derivedFeatures") : error.getMessage();
}
```

- [ ] **Step 2: Run the self-test and verify RED**

Run: `./scripts/run-self-test.sh`

Expected: compilation fails because `FeatureConfig` and the unified accessors do not exist. This is the intended missing-feature failure, not a syntax error in the test.

- [ ] **Step 3: Implement the unified DTO and obsolete-property rejection**

Create `DefinitionType`:

```java
package com.example.featuredag.config;

public enum DefinitionType {
    BASE,
    DERIVED
}
```

Create `FeatureConfig` by combining the existing DTO fields. Keep Jackson names and flexible booleans exactly as before, and expose these DAG accessors:

```java
public String definitionType() { return definitionType; }
public String expression() { return expression; }
public List<String> entityScopes() {
    return entityScopes == null ? List.of() : List.copyOf(entityScopes);
}
public String valueShape() { return valueShape; }
public String outputPolicy() { return outputPolicy; }
```

Change `FeatureSetConfig` to:

```java
@JsonProperty("features")
private List<FeatureConfig> features = List.of();

public List<FeatureConfig> features() {
    return features == null ? List.of() : List.copyOf(features);
}
```

Remove the typed `derivedFeatures` property. In `FeatureConfigLoader.load(String)`, validate the parsed object before returning it:

```java
FeatureSetConfig config = OBJECT_MAPPER.readValue(json, FeatureSetConfig.class);
if (config.additionalProperties().containsKey("derivedFeatures")) {
    throw new IllegalArgumentException(
            "Obsolete top-level property derivedFeatures is not supported; use features");
}
return config;
```

- [ ] **Step 4: Rewrite mapping around normalized unified entries**

Normalize definition type with null/blank BASE semantics:

```java
private static DefinitionType parseDefinitionType(String value, String featureName) {
    if (value == null || value.isBlank()) return DefinitionType.BASE;
    return parseEnum(DefinitionType.class, value,
            "definition_type for feature " + featureName);
}
```

Iterate `config.features()` once. BASE definitions require `raw_name`, reject a non-blank expression, resolve configured scopes/overrides, preserve current missing-scope fallback, and always use effective `OutputPolicy.OUTPUT`. DERIVED definitions require an expression, default blank output policy to `OUTPUT`, retain their configured scopes for later inference validation, and enter the derived target/output collection.

Use one entry record for duplicate detection and target validation:

```java
private record DefinitionEntry(
        DefinitionType definitionType,
        boolean enabled,
        OutputPolicy outputPolicy,
        int declarationIndex) {
    boolean base() { return definitionType == DefinitionType.BASE; }
}

private record DerivedEntry(
        FeatureConfig config,
        FeatureDefinition definition,
        int declarationIndex) {}
```

Update every error field path to `features[].name`, change `selectTargets` to reject `entry.base()`, and retain disabled-reference checks, declaration-order output sorting, and duplicate `store_name` checks.

- [ ] **Step 5: Mechanically migrate existing test fixtures to the unified array**

For each existing self-test JSON fixture, move derived objects into `features` and mark them with `"definition_type":"DERIVED"`. Do not add shape/scope assertions yet. Preserve the exact expressions, policies, order values, and string-replacement anchors so this step changes only the container format.

- [ ] **Step 6: Run the self-test and verify GREEN for unified loading/mapping**

Run: `./scripts/run-self-test.sh`

Expected: `All DAG engine self tests passed.` Production sources compile without `RawFeatureConfig` or `DerivedFeatureConfig` references.

- [ ] **Step 7: Commit the coherent configuration change**

```bash
git add src/main/java/com/example/featuredag/config src/test/java/com/example/featuredag/DagEngineSelfTest.java
git commit -m "Unify base and derived feature configuration"
```

### Task 2: Declared Shape and Scope Semantics

**Files:**
- Modify: `src/main/java/com/example/featuredag/definition/FeatureDefinition.java`
- Modify: `src/main/java/com/example/featuredag/logical/LogicalDagBuilder.java`
- Modify: `src/main/java/com/example/featuredag/config/FeatureConfigMapper.java`
- Test: `src/test/java/com/example/featuredag/DagEngineSelfTest.java`

**Interfaces:**
- Consumes: optional configuration strings `value_shape` and `entity_scopes` on unified declarations.
- Produces: `FeatureDefinition.declaredValueShape(): ValueShape`; source-node declared shapes; derived inference mismatch failures.

- [ ] **Step 1: Add failing shape and scope tests**

Add one success test that builds a unified configuration containing BASE `SCALAR`/`SEQUENCE`, a derived online candidate result declared as `VECTOR`, and correctly declared derived scopes. Inspect built feature output nodes and assert the mapping:

```java
assert dag.node(dag.featureOutputNodeIds().get("candidate_score")).valueShape()
        == ValueShape.CANDIDATE_VECTOR;
```

Add focused failure assertions:

```java
DagBuildException shapeError = expectThrows(DagBuildException.class,
        () -> buildDag(configWithDerivedShape("SCALAR")));
assert shapeError.getMessage().contains(
        "Declared value shape mismatch for feature candidate_score");
DagBuildException scopeError = expectThrows(DagBuildException.class,
        () -> buildDag(configWithDerivedScopes("USER")));
assert scopeError.getMessage().contains(
        "Declared entity scopes mismatch for feature candidate_score");
IllegalArgumentException enumError = expectThrows(IllegalArgumentException.class,
        () -> map(configWithValueShape("MATRIX")));
assert enumError.getMessage().contains("Invalid value_shape for feature");
```

Also assert that absent BASE `value_shape` still maps `EVENT_SEQUENCE` to `SEQUENCE` and ordinary scalar types to `SCALAR`.

- [ ] **Step 2: Run the focused self-test and verify RED**

Run: `./scripts/run-self-test.sh`

Expected: failures show that configured shapes are not stored/applied and derived scope/shape mismatches are not detected.

- [ ] **Step 3: Store and parse declared value shapes**

Add an optional field and builder method to `FeatureDefinition`:

```java
private final ValueShape declaredValueShape;

public ValueShape declaredValueShape() { return declaredValueShape; }

public Builder declaredValueShape(ValueShape value) {
    this.declaredValueShape = value;
    return this;
}
```

Map configuration values explicitly so the external vocabulary stays isolated:

```java
private static ValueShape parseValueShape(String value, String featureName) {
    if (value == null || value.isBlank()) return null;
    return switch (value.trim().toUpperCase(Locale.ROOT)) {
        case "SCALAR" -> ValueShape.SCALAR;
        case "SEQUENCE" -> ValueShape.SEQUENCE;
        case "VECTOR" -> ValueShape.CANDIDATE_VECTOR;
        default -> throw new IllegalArgumentException(
                "Invalid value_shape for feature " + featureName + ": " + value);
    };
}
```

For BASE and DERIVED mappings, set `declaredValueShape`. For DERIVED mappings, parse configured scopes into `FeatureDefinition.entityScopes`; an empty list remains “no constraint.”

- [ ] **Step 4: Apply source declarations and validate derived inference**

In source creation, prefer the declaration:

```java
ValueShape shape = definition.declaredValueShape() == null
        ? shapeForType(definition.dataType())
        : definition.declaredValueShape();
```

After the producer is built and type validation runs, validate only DERIVED declarations:

```java
private static void validateDeclaredShapeAndScopes(
        FeatureDefinition definition, LogicalNode producer) {
    if (definition.isRaw()) return;
    if (definition.declaredValueShape() != null
            && definition.declaredValueShape() != producer.valueShape()) {
        throw new DagBuildException(
                "Declared value shape mismatch for feature " + definition.name()
                        + ": declared=" + definition.declaredValueShape()
                        + ", inferred=" + producer.valueShape());
    }
    if (!definition.entityScopes().isEmpty()
            && !definition.entityScopes().equals(producer.entityScopes())) {
        throw new DagBuildException(
                "Declared entity scopes mismatch for feature " + definition.name()
                        + ": declared=" + definition.entityScopes()
                        + ", inferred=" + producer.entityScopes());
    }
}
```

Call this before creating the `FeatureOutputNode` so failures occur during DAG initialization.

- [ ] **Step 5: Run the self-test and verify GREEN**

Run: `./scripts/run-self-test.sh`

Expected: all declaration success/mismatch tests pass, including `VECTOR` to `CANDIDATE_VECTOR` and missing BASE shape inference.

- [ ] **Step 6: Commit declared-shape support**

```bash
git add src/main/java/com/example/featuredag/definition/FeatureDefinition.java src/main/java/com/example/featuredag/logical/LogicalDagBuilder.java src/main/java/com/example/featuredag/config/FeatureConfigMapper.java src/test/java/com/example/featuredag/DagEngineSelfTest.java
git commit -m "Validate declared feature shapes and scopes"
```

### Task 3: Update Validation Mutations, Demo, and Documentation

**Files:**
- Modify: `src/test/java/com/example/featuredag/DagEngineSelfTest.java`
- Modify: `src/main/java/com/example/featuredag/demo/DagDemo.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: the unified JSON contract implemented in Tasks 1 and 2.
- Produces: robust unified-format validation coverage, a runnable demo JAR, and user-facing documentation containing no supported split-format example.

- [ ] **Step 1: Enrich the central acceptance fixture with explicit declarations**

Add `"definition_type":"BASE"` to representative base records while deliberately leaving several historical base records absent/null/blank to retain compatibility coverage. Add matching shape and scope declarations to representative derived records.

The central shape becomes:

```json
{
  "features": [
    {"name":"price","raw_name":"raw_price","type":"DOUBLE",
     "definition_type":"BASE","entity_scopes":["ITEM"],"value_shape":"SCALAR"},
    {"name":"normalized_price","type":"DOUBLE","definition_type":"DERIVED",
     "expression":"normalize(price, {\"min\":0,\"max\":1000})",
     "value_shape":"SCALAR","output_policy":"INTERNAL_ONLY"},
    {"name":"price_score","store_name":"price_score_out","type":"DOUBLE",
     "definition_type":"DERIVED","expression":"multiply(normalized_price, price)",
     "value_shape":"SCALAR","output_policy":"OUTPUT"}
  ],
  "feature_set_name":"test_001",
  "version":"latest"
}
```

Run: `./scripts/run-self-test.sh`

Expected: `All DAG engine self tests passed.`

- [ ] **Step 2: Update mutation-based validation tests**

Replace string mutations that assume a separate `derivedFeatures` block with mutations anchored on `"definition_type":"DERIVED"`, feature names, or output policy fields. Preserve all existing assertions for disabled dependency references, requested target validation, duplicate outputs, default conversion, online scopes, target order, and online/offline consistency.

Run: `./scripts/run-self-test.sh`

Expected: `All DAG engine self tests passed.`

- [ ] **Step 3: Convert the runnable demo**

Move every demo derived declaration into the single `features` array, mark it `DERIVED`, and add representative `value_shape` and `entity_scopes` declarations only where they match inference.

Run: `mvn -q clean package`

Expected: exit code 0 and both thin and shaded JARs are generated.

Run: `java -jar target/feature-dag-engine-1.0.0-SNAPSHOT-all.jar`

Expected: the demo prints its logical/physical plans and generated results without a configuration exception.

- [ ] **Step 4: Rewrite README configuration documentation**

Replace the statement that the engine adds `derivedFeatures` with the unified-array contract. Document all four explicitly enumerated fields plus `expression`, null/blank BASE compatibility, derived shape/scope inference validation, the `VECTOR` mapping, and rejection of old top-level `derivedFeatures`.

Use a single JSON example whose BASE and DERIVED declarations coexist under `features`.

- [ ] **Step 5: Run full fresh verification**

Run: `./scripts/run-self-test.sh`

Expected: `All DAG engine self tests passed.`

Run: `mvn clean package`

Expected: `BUILD SUCCESS` with exit code 0.

Run: `java -jar target/feature-dag-engine-1.0.0-SNAPSHOT-all.jar`

Expected: exit code 0 with demo output and no exception.

Run: `rg -n 'derivedFeatures|RawFeatureConfig|DerivedFeatureConfig' README.md src/main src/test`

Expected: only the intentional obsolete-format rejection and its regression test mention `derivedFeatures`; there are no references to either deleted DTO class.

- [ ] **Step 6: Commit the migrated fixtures and documentation**

```bash
git add README.md src/main/java/com/example/featuredag/demo/DagDemo.java src/test/java/com/example/featuredag/DagEngineSelfTest.java
git commit -m "Migrate examples to unified feature configuration"
```
