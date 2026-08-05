# Business Acceptance UT Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert every stable, engine-local scenario in `特征表达式DAG引擎业务验收测试方案.docx` into deterministic Java assertion tests.

**Architecture:** Extend `DagEngineSelfTest` with scenario-oriented methods grouped by configuration/DAG semantics, offline-online execution, candidate deduplication/SequenceView behavior, and consistency. Only adjust production behavior when a newly added acceptance test proves the current implementation contradicts a supported P0/P1 engine contract.

**Tech Stack:** Java 21, Maven, Java `assert`, existing Feature DAG API and runtime.

## Global Constraints

- Keep tests deterministic and dependency-free beyond existing Maven dependencies.
- Add end-to-end scenarios to `src/test/java/com/example/featuredag/DagEngineSelfTest.java` as required by `AGENTS.md`.
- Derive expected values as literals: industry counts `[3, 1, 3, 0]` and numeric tolerance `1e-9`.
- Do not mock DAG, planner, runtime, or operators.
- Do not claim UT coverage for feature-platform publishing, model training artifacts, hot-update routing, RPC, SLA load testing, capacity limits, or cache/index failure injection; those components do not exist in this repository.
- Use JDK 21 at `C:\Program Files\Microsoft\jdk-21` for verification.
- The workspace has no `.git` directory; do not initialize one and omit commit steps.

---

### Task 1: Configuration and DAG Business Semantics

**Files:**

- Modify: `src/test/java/com/example/featuredag/DagEngineSelfTest.java`
- Modify only if RED proves necessary: `src/main/java/com/example/featuredag/operator/OperatorRegistry.java`

**Interfaces:**

- Consumes: `LogicalDagBuilder.build(List<FeatureDefinition>, Set<String>)`.
- Verifies: FP-003~FP-008, DG-001~DG-010, ER-002, ER-003, ER-010.

- [ ] Add `testDagBusinessSemantics()` before production changes. It must assert:

```java
LogicalDag online = builder.build(definitions, linkedSet("same_industry_count", "final_score"));
assert online.featureOutputNodeIds().keySet().containsAll(Set.of(
        "same_industry_count", "same_industry_seq", "user_seq1", "item_industry",
        "final_score", "user_click_score", "user_click_count", "item_price_log", "item_price"));
assert online.nodes().values().stream()
        .filter(node -> "user_click_score".equals(node.sourceFeatureName()))
        .count() >= 1;
assert online.featureOutput("user_click_score").entityScopes().equals(Set.of(EntityScope.USER));
assert online.featureOutput("item_price_log").entityScopes().equals(Set.of(EntityScope.ITEM));
assert online.featureOutput("same_industry_count").entityScopes()
        .equals(Set.of(EntityScope.USER, EntityScope.ITEM));
assert online.featureOutput("same_industry_seq").valueShape() == ValueShape.SEQUENCE;
assert online.featureOutput("same_industry_count").valueShape() == ValueShape.SCALAR;
```

Build an offline DAG whose ordered targets start with the two online targets, then add all other outputs; assert `online.nodes().keySet()` is a subset of `offline.nodes().keySet()`. Assert one `feature:user_click_score` node exists even when multiple roots consume it.

- [ ] Add independent `expectThrows` assertions for an unknown feature, unclosed expression, unknown operator, missing normalize argument, cycle, and `count(item_price)` type mismatch. Each assertion must check the offending feature/operator text.

- [ ] Run the assertion main. Expected RED: `count(item_price)` is currently accepted during inference.

- [ ] Make the minimal production fix in `OperatorRegistry`: the `count` inference lambda rejects inputs whose `valueShape` is not `SEQUENCE` and whose type is not `OBJECT`, with a message containing operator `count`, expected sequence/collection, and actual type/shape.

- [ ] Re-run the complete assertion main and require exit 0.

---

### Task 2: Offline/Online Execution and Stage Semantics

**Files:**

- Modify: `src/test/java/com/example/featuredag/DagEngineSelfTest.java`
- Modify only if RED proves necessary: `src/main/java/com/example/featuredag/api/OnlineGenerateRequest.java`
- Modify only if RED proves necessary: `src/main/java/com/example/featuredag/runtime/ExecutionContext.java`

**Interfaces:**

- Consumes: `FeatureDagEngine.init`, `FeatureDagEngine.generate`, `PhysicalPlanner.plan`.
- Verifies: TR-001, TR-002, TR-004~TR-009, ON-002, ON-003, ON-006~ON-010, EX-001~EX-008.

- [ ] Add `testExecutionStagesAndTargetSelection()`:

```java
PhysicalPlan plan = planner.plan(optimizer.analyze(onlineDag), ExecutionEnvironment.ONLINE, "stage-test");
assert stageFor(plan, "source:user_click_count") == ExecutionStage.REQUEST_SHARED;
assert stageFor(plan, "source:item_price") == ExecutionStage.CANDIDATE_BATCH;
assert stageFor(plan, "feature:user_click_score") == ExecutionStage.REQUEST_SHARED;
assert stageFor(plan, "feature:item_price_log") == ExecutionStage.CANDIDATE_BATCH;
assert stageFor(plan, "feature:final_score") == ExecutionStage.CANDIDATE_BATCH;
```

Add `scene_hour` and `user_scene_score=add(user_click_count,scene_hour)` definitions and assert SCENE and USER+SCENE nodes remain `REQUEST_SHARED`.

- [ ] Add `testCandidateCardinalityAndDefaults()` before production changes. It must cover zero candidates, one candidate, four candidates, a missing `item_price` with default `0.0`, and missing `item_price` without a default. Expected literal results include empty candidate output, single count `3`, and defaulted `final_score=0.0`.

- [ ] Run the assertion main. Expected RED: zero-candidate construction is rejected.

- [ ] Remove the non-empty-candidate restriction from `OnlineGenerateRequest` and `ExecutionContext.onlineRequest`; retain null checks and immutable copies. Verify the existing vectorized runtime returns an empty candidate list without evaluating candidate operators incorrectly.

- [ ] Add `testEmptySequenceAndOfflineOutputSet()`: an empty `SequenceBlock` must yield an empty sequence and count `0`; an offline config with multiple `OUTPUT` features returns every output while `INTERNAL_ONLY` remains absent.

- [ ] Re-run the complete assertion main and require exit 0.

---

### Task 3: Candidate Deduplication and SequenceView

**Files:**

- Modify: `src/test/java/com/example/featuredag/DagEngineSelfTest.java`

**Interfaces:**

- Consumes: `DagRuntime.execute`, `SequenceView.filterByIndustry`, `SequenceView.slice`.
- Verifies: SC1-001~SC1-007, SC2-001~SC2-008, PF-001~PF-003 at deterministic structural/metric level.

- [ ] Replace the five-event helper with the six-event UAT baseline: industry1=3, industry2=1, industry3=2. Add four candidates in order item1/2/3/4 and assert counts `[3, 1, 3, 0]`, dedup metrics `4 -> 3`, and reordered candidates item3/item2/item1 map to `[3, 1, 3]`.

- [ ] Assert the online count-only plan contains `COUNT_INDUSTRY_BATCH`; the offline plan with sequence output contains no fused node and returns a `SequenceView` sharing the original `SequenceBlock`.

- [ ] Add `testSequenceSelectionStrategies()` using literal event layouts:

```java
assert SequenceView.slice(block, 2, 5).selection() instanceof RangeSelection;
assert SequenceView.filterByIndustry(sparseBlock, "keep").selection() instanceof IndexSelection;
assert SequenceView.filterByIndustry(denseBlock, "keep").selection() instanceof BitmapSelection;
SequenceView chained = SequenceView.slice(SequenceView.filterByIndustry(block, "industry1"), 1, 3);
assert chained.baseBlock() == block;
assert chained.baseIndexAt(0) == 2;
```

- [ ] Assert public output materializes an `OUTPUT` sequence as ordinary event maps, while internal count consumption still receives a view and produces the correct count.

- [ ] Run the complete assertion main and require exit 0.

---

### Task 4: Offline/Online Consistency and Coverage Matrix

**Files:**

- Modify: `src/test/java/com/example/featuredag/DagEngineSelfTest.java`
- Create: `docs/testing/business-acceptance-ut-coverage.md`

**Interfaces:**

- Consumes: the same JSON config and sample values through offline and online engines.
- Verifies: CO-001~CO-008 and the engine-local portions of the acceptance entry criteria.

- [ ] Add `testOfflineOnlineConsistency()` that evaluates item1/2/3/4 as four offline rows and one online four-candidate request. For each index assert identical `same_industry_count` and `abs(offlineFinalScore-onlineFinalScore) <= 1e-9`. Repeat for missing `user_click_count` default and empty sequence.

- [ ] Add a reordered online request with explicit `item_id`; compare each result against the offline result selected by item ID, proving deduplication never changes candidate mapping.

- [ ] Create the coverage matrix with three statuses:

```text
UT covered: FP/DG/TR engine-local cases, ON-006~010, EX, SC1, SC2 structural cases, CO, core ER.
Already covered by existing tests: JSON/path init, concurrency, internal output filtering, config validation.
External/integration scope: feature-platform publish, multi-model training artifacts, model hot-update routing, RPC, SLA/OOM/limits, cache/index failure injection.
```

List the exact scenario IDs in each group; do not mark external cases as passed.

- [ ] Run fresh verification:

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q clean package
mvn -q -DskipTests test-compile dependency:build-classpath "-Dmdep.outputFile=target/test-classpath.txt"
$cp = "target/test-classes;target/classes;" + (Get-Content -Raw target/test-classpath.txt)
java -ea -cp $cp com.example.featuredag.DagEngineSelfTest
```

Expected: Maven exits 0 and the assertion main prints `All DAG engine self tests passed.`.
