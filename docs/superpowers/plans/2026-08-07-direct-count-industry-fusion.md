# Direct Count-Industry Fusion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fuse direct nested `count(extractIndustry(...))` expressions online without removing shared logical nodes, while preserving the existing named-intermediate fusion path.

**Architecture:** Represent a recognized count/extract pattern as one immutable planning record containing the count node ID, extract node ID, and zero or one intermediate feature-output IDs. The optimizer and physical planner consume the same match representation so direct and wrapped shapes cannot drift apart; the planner applies reference-count and root checks before eliminating any matched node.

**Tech Stack:** Java 21, Maven, dependency-free Java `assert` self-tests.

## Global Constraints

- Keep logical DAG construction unchanged; do not add anonymous `FeatureOutputNode` objects.
- Apply `COUNT_INDUSTRY_BATCH` only to online plans.
- Fall back to generic operators when the extract node or optional intermediate output is shared or observable.
- Preserve the existing public `matchesCountExtractIndustry(LogicalDag, OperatorNode)` method.
- Add no external runtime or test dependencies.
- Use four-space indentation, UTF-8, explicit imports, and one public top-level type per file.

---

### Task 1: Recognize and safely plan both fusion shapes

**Files:**
- Create: `src/main/java/com/example/featuredag/planning/CountExtractIndustryMatch.java`
- Modify: `src/main/java/com/example/featuredag/planning/LogicalDagOptimizer.java:46-73`
- Modify: `src/main/java/com/example/featuredag/physical/PhysicalPlanner.java:25-66,162-187`
- Test: `src/test/java/com/example/featuredag/DagEngineSelfTest.java:61-84,928-985`

**Interfaces:**
- Consumes: `LogicalDag`, `OperatorNode`, `FeatureOutputNode`, `PlannerMetadata.referenceCount()`, and `LogicalDag.rootNodeIds()`.
- Produces: `CountExtractIndustryMatch(String countNodeId, String extractNodeId, List<String> intermediateNodeIds)` and `LogicalDagOptimizer.matchCountExtractIndustry(LogicalDag, OperatorNode): Optional<CountExtractIndustryMatch>`.

- [ ] **Step 1: Add a failing end-to-end regression test**

Add `testDirectNestedCountIndustryFusion()` to `DagEngineSelfTest` and invoke it immediately after `testCandidateDeduplicationAndFusion()` in `main`:

```java
private static void testDirectNestedCountIndustryFusion() {
    OperatorRegistry operators = OperatorRegistry.standard();
    LogicalDagBuilder builder = new LogicalDagBuilder(new ExpressionParser(), operators);
    LogicalDagOptimizer optimizer = new LogicalDagOptimizer();
    PhysicalPlanner planner = new PhysicalPlanner();
    DagRuntime runtime = new DagRuntime(operators);

    List<FeatureDefinition> directDefinitions = List.of(
            FeatureDefinition.raw("user_seq1", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
            FeatureDefinition.raw("item_industry", DataType.STRING, EntityScope.ITEM, "unknown"),
            FeatureDefinition.derived(
                    "same_industry_count",
                    DataType.INT,
                    "count(extractIndustry(user_seq1, item_industry))",
                    OutputPolicy.OUTPUT));
    LogicalDag directDag = builder.build(directDefinitions, Set.of("same_industry_count"));
    PhysicalPlan onlinePlan = planner.plan(
            optimizer.analyze(directDag), ExecutionEnvironment.ONLINE, "direct-nested-online");
    assert onlinePlan.nodes().stream()
            .anyMatch(node -> node.executorType() == ExecutorType.COUNT_INDUSTRY_BATCH)
            : "Direct nested count/extractIndustry should fuse online";

    ExecutionResult result = runtime.execute(
            onlinePlan,
            ExecutionContext.onlineRequest(
                    "direct-nested-request",
                    Map.of("user_seq1", sequence()),
                    fourCandidates()));
    CandidateVectorValue counts = (CandidateVectorValue) result.feature("same_industry_count");
    assert counts.values().equals(List.of(3, 1, 3, 0)) : counts.values();
    assert result.nodeStates().values().stream()
            .anyMatch(state -> state.dedupInputCount() == 4 && state.uniqueInputCount() == 3)
            : "Direct nested fusion should deduplicate candidate industries";

    PhysicalPlan offlinePlan = planner.plan(
            optimizer.analyze(directDag), ExecutionEnvironment.OFFLINE, "direct-nested-offline");
    assert offlinePlan.nodes().stream()
            .noneMatch(node -> node.executorType() == ExecutorType.COUNT_INDUSTRY_BATCH)
            : "Direct nested count/extractIndustry must remain unfused offline";

    List<FeatureDefinition> sharedDefinitions = List.of(
            FeatureDefinition.raw("user_seq1", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
            FeatureDefinition.raw("item_industry", DataType.STRING, EntityScope.ITEM, "unknown"),
            FeatureDefinition.derived(
                    "same_industry_seq",
                    DataType.EVENT_SEQUENCE,
                    "extractIndustry(user_seq1, item_industry)",
                    OutputPolicy.OUTPUT),
            FeatureDefinition.derived(
                    "same_industry_count",
                    DataType.INT,
                    "count(extractIndustry(user_seq1, item_industry))",
                    OutputPolicy.OUTPUT));
    LogicalDag sharedDag = builder.build(
            sharedDefinitions, linkedSet("same_industry_seq", "same_industry_count"));
    PhysicalPlan sharedPlan = planner.plan(
            optimizer.analyze(sharedDag), ExecutionEnvironment.ONLINE, "shared-extract-online");
    assert sharedPlan.nodes().stream()
            .noneMatch(node -> node.executorType() == ExecutorType.COUNT_INDUSTRY_BATCH)
            : "A shared extractIndustry operator must not be eliminated by fusion";
}
```

- [ ] **Step 2: Run the self-test and verify RED**

Run from PowerShell:

```powershell
mvn -q -DskipTests test-compile dependency:build-classpath "-Dmdep.outputFile=target/test-classpath.txt"
$cp = "target/test-classes;target/classes;" + (Get-Content -LiteralPath "target/test-classpath.txt" -Raw).Trim()
java -ea -cp $cp com.example.featuredag.DagEngineSelfTest
```

Expected: the process exits non-zero at `Direct nested count/extractIndustry should fuse online`; this proves the regression test observes the missing fusion rather than a setup error.

- [ ] **Step 3: Add the shared immutable match representation**

Create `CountExtractIndustryMatch.java`:

```java
package com.example.featuredag.planning;

import java.util.List;
import java.util.Objects;

public record CountExtractIndustryMatch(
        String countNodeId,
        String extractNodeId,
        List<String> intermediateNodeIds) {
    public CountExtractIndustryMatch {
        Objects.requireNonNull(countNodeId, "countNodeId");
        Objects.requireNonNull(extractNodeId, "extractNodeId");
        intermediateNodeIds = List.copyOf(intermediateNodeIds);
    }
}
```

- [ ] **Step 4: Extend optimizer matching without changing DAG construction**

In `LogicalDagOptimizer`, import `java.util.Optional`, add `matchCountExtractIndustry`, and make the existing boolean method delegate to it:

```java
public Optional<CountExtractIndustryMatch> matchCountExtractIndustry(
        LogicalDag dag, OperatorNode countNode) {
    if (!"count".equals(countNode.operatorName()) || countNode.inputs().size() != 1) {
        return Optional.empty();
    }
    LogicalNode input = dag.node(countNode.inputs().get(0).nodeId());
    OperatorNode extract;
    List<String> intermediateNodeIds;
    if (input instanceof OperatorNode directOperator) {
        extract = directOperator;
        intermediateNodeIds = List.of();
    } else if (input instanceof FeatureOutputNode featureOutput) {
        LogicalNode producer = dag.node(featureOutput.producerNodeId());
        if (!(producer instanceof OperatorNode producerOperator)) return Optional.empty();
        extract = producerOperator;
        intermediateNodeIds = List.of(featureOutput.nodeId());
    } else {
        return Optional.empty();
    }
    if (!"extractIndustry".equals(extract.operatorName()) || extract.inputs().size() != 2) {
        return Optional.empty();
    }
    return Optional.of(new CountExtractIndustryMatch(
            countNode.nodeId(), extract.nodeId(), intermediateNodeIds));
}

public boolean matchesCountExtractIndustry(LogicalDag dag, OperatorNode countNode) {
    return matchCountExtractIndustry(dag, countNode).isPresent();
}
```

Leave `analyze` calling `matchesCountExtractIndustry` so its metadata behavior remains compatible.

- [ ] **Step 5: Make the physical planner consume the shared match and enforce safety**

Replace the private `FusionMatch` type with `CountExtractIndustryMatch`. In `findOnlineFusionMatches`, resolve the shared match once, then reject any match whose extract node has more than one reference or whose optional intermediate node is a root or has more than one reference:

```java
CountExtractIndustryMatch match = optimizer.matchCountExtractIndustry(dag, countNode).orElse(null);
if (match == null) continue;
if (optimized.metadata().node(match.extractNodeId()).referenceCount() != 1) continue;
boolean unsafeIntermediate = match.intermediateNodeIds().stream().anyMatch(intermediateNodeId ->
        dag.rootNodeIds().contains(intermediateNodeId)
                || optimized.metadata().node(intermediateNodeId).referenceCount() != 1);
if (unsafeIntermediate) continue;
result.put(nodeId, match);
```

Build `skippedLogicalNodes` from `extractNodeId()` and `intermediateNodeIds()`. When creating the fused physical node, construct its logical IDs in topological order:

```java
List<String> fusedLogicalNodeIds = new ArrayList<>();
fusedLogicalNodeIds.add(fusion.extractNodeId());
fusedLogicalNodeIds.addAll(fusion.intermediateNodeIds());
fusedLogicalNodeIds.add(logicalNodeId);
```

Use `fusedLogicalNodeIds` in the `PhysicalNode` constructor and remove the old private `FusionMatch` record.

- [ ] **Step 6: Run the complete self-test and verify GREEN**

Run:

```powershell
mvn -q -DskipTests test-compile dependency:build-classpath "-Dmdep.outputFile=target/test-classpath.txt"
$cp = "target/test-classes;target/classes;" + (Get-Content -LiteralPath "target/test-classpath.txt" -Raw).Trim()
java -ea -cp $cp com.example.featuredag.DagEngineSelfTest
```

Expected: exit code `0` and `All DAG engine self tests passed.` The direct online expression fuses, the offline and shared-node plans do not fuse, and existing named-intermediate behavior remains green.

- [ ] **Step 7: Run packaging verification**

Run:

```powershell
mvn clean package
```

Expected: exit code `0` and both thin and shaded JARs under `target/`. Note that Maven compiles but does not execute `DagEngineSelfTest`, so Step 6 remains mandatory.

- [ ] **Step 8: Inspect the final diff and commit**

Run:

```powershell
git diff --check
git diff -- src/main/java/com/example/featuredag/planning/CountExtractIndustryMatch.java src/main/java/com/example/featuredag/planning/LogicalDagOptimizer.java src/main/java/com/example/featuredag/physical/PhysicalPlanner.java src/test/java/com/example/featuredag/DagEngineSelfTest.java
git add -- src/main/java/com/example/featuredag/planning/CountExtractIndustryMatch.java src/main/java/com/example/featuredag/planning/LogicalDagOptimizer.java src/main/java/com/example/featuredag/physical/PhysicalPlanner.java src/test/java/com/example/featuredag/DagEngineSelfTest.java docs/superpowers/plans/2026-08-07-direct-count-industry-fusion.md
git commit -m "Fix direct count industry fusion"
```

Expected: no whitespace errors; the commit contains only the match model, optimizer/planner changes, regression tests, and this implementation plan.
