# Direct Count-Industry Fusion Design

## Goal

Make online planning fuse both supported expression shapes for counting events in the candidate industry:

```text
count(extractIndustry(user_seq1, item_industry))
count(same_industry_seq)
```

where `same_industry_seq` is produced by `extractIndustry(user_seq1, item_industry)`.

Offline planning remains unfused. Online planning must also remain unfused whenever eliminating an `extractIndustry` or intermediate feature-output node would remove a value that another consumer or root output still needs.

## Current Problem

`LogicalDagBuilder` represents a nested call as a direct `OperatorNode -> OperatorNode` edge. `LogicalDagOptimizer.matchesCountExtractIndustry` only recognizes `count -> FeatureOutputNode -> extractIndustry`. Consequently, direct nesting receives no `COUNT_EXTRACT_INDUSTRY` fusion metadata and becomes two generic physical operators.

The existing named-intermediate path works and must remain compatible.

## Chosen Design

Extend the optimizer's fusion-pattern recognition to accept either of these logical shapes:

```text
count -> extractIndustry
count -> FeatureOutputNode -> extractIndustry
```

Keep logical DAG construction unchanged. The absence of anonymous `FeatureOutputNode` objects for nested calls is a valid compact representation and should not be changed solely for one physical optimization.

Update physical fusion matching so a match contains the count node, extract node, and an optional intermediate feature-output node. Only nodes present in the match are skipped and represented by the fused physical node.

## Safety Rules

A fusion is permitted only when every logical node removed from standalone physical planning is safe to eliminate:

- The optional intermediate `FeatureOutputNode` is not a DAG root and has exactly one reference.
- The `extractIndustry` operator has exactly one reference after accounting for the matched shape. A shared extract operator must remain unfused because another feature output or operator still requires its slot.
- The count operator has exactly one input.
- The extract operator has exactly two inputs.
- The optimization is applied only in the `ONLINE` environment.

If any condition fails, planning falls back to the existing generic operators. No new exception or partial fusion is introduced.

## Physical Plan

For a safe match, emit one `COUNT_INDUSTRY_BATCH` node. Its inputs remain the sequence and industry inputs of `extractIndustry`. Its `logicalNodeIds` contain the extract node, the optional intermediate node when present, and the count node. The count node's output slot continues to feed the final feature output.

Runtime behavior is unchanged: candidate industries are deduplicated, an industry index is built or reused, counts are computed for unique industries, and results are mapped back to candidate order.

## Tests

Add dependency-free assertions to `DagEngineSelfTest` covering:

1. A direct nested online expression produces `COUNT_INDUSTRY_BATCH`.
2. The same direct nested expression remains unfused offline.
3. Runtime results for repeated candidate industries are correct and record candidate deduplication.
4. A direct nested expression sharing its canonical `extractIndustry` operator with an observable feature output remains unfused.
5. The existing named-intermediate fusion tests continue to pass.

## Non-Goals

- Do not add anonymous feature-output nodes during logical DAG construction.
- Do not change generic operator semantics.
- Do not extend fusion to other operator combinations.
- Do not change configuration syntax or public APIs.
