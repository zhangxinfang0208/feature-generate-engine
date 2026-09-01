# Feature Fields and Reachability

`name` is the feature identity. A frontend property object's `raw_name` is not a substitute for feature `name`. Treat `null` and an empty or blank string as missing.

## Completion rules

BASE completion always has two passes. First collect the current frontend entry for every referenced BASE feature. The business may provide a BASE-only array or a full model configuration: match entries by exact `name`, select only AST-referenced BASE names, retain AST first-seen order, and ignore unrelated entries. `raw_name` is never an identity substitute. A referenced name with no entry remains missing; duplicate entries for a referenced name are a conflict. Do not ask the business to fill individual fields until all current entries have been received. Second, check those entries and request only unresolved facts, grouped by feature in one reply. Existing fields are evidence to validate, absent fields are candidates for additions, and contradictory populated fields are conflicts for manual correction.

Every BASE feature needs `definition_type=BASE`, `type`, `value_shape`, `entity_scopes`, and `seq_max_length`. For an absent `definition_type`, emit the deterministic `BASE` addition; for absent `to_use`, emit deterministic `true`. Do not ask the business for either value. Existing non-`BASE` definition type or `to_use=false` is a conflict for manual correction. Do not infer any other unresolved BASE fact from a name or expression: ask the business user even when asked not to ask questions. In the business reply template, accept `entity` as the low-cost input label and map it to `entity_scopes`; comma-separated values map to a scope list. `dft` is optional, but when present it must be compatible with the declared type and shape.

Every DERIVED feature needs the complete declaration `name`, `definition_type=DERIVED`, `expression`, `output_policy=OUTPUT`, `type`, `value_shape`, `entity_scopes`, `seq_max_length`, and `to_use=true`. `name` is the feature identity and is not a frontend property addition. `output_policy=OUTPUT` and `to_use=true` are deterministic additions when absent; unresolved operator semantics must not be used to invent type, shape, scope, or length. For a reachable BASE, `to_use=true` is also required; an existing `to_use=false` is a conflict.

During final validation, a missing reachable field is still a declaration defect and makes the verdict `FAIL`. Before returning that verdict, fill every missing field that the available operator contract determines exactly. For example, `add(scalar_a, scalar_b)` has scalar output and fixed `seq_max_length=1`, while `add(sequence_a, scalar_b)` has sequence output and derives its maximum length from `sequence_a`. Emit an absent numeric length as a five-key `NUMBER` property with an unquoted numeric `data_value`. Ask the business only when the contract or required inputs do not determine the value.

Supported business data types are `INT`, `BIGINT`, `DOUBLE`, `STRING`, `BOOLEAN`, `OBJECT`, and `EVENT_SEQUENCE`. `UNKNOWN` is not a completed declaration; it means the fact remains unresolved. Ordinary business inputs use `SCALAR` or `SEQUENCE`; `EVENT_SEQUENCE` requires `SEQUENCE`. Scopes are `USER`, `SCENE`, and `ITEM`. Compare a declared DERIVED scope as an exact set, ignoring order and duplicates, against its inferred scope.

`seq_max_length` must be positive for every sequence. A sequence may validly have maximum length `1`; therefore a length of `1` never substitutes for an explicit `value_shape`. Do not infer shape from the operator name alone: `discrete`, `log_base`, `min`, `max`, `add`, `sub`, `mul`, and `div` can return either `SCALAR` or `SEQUENCE` according to their inputs. `append` always returns `SEQUENCE`, and `join` always returns `SCALAR`. Apply the exact length formula from [operator contracts](operator-contracts.md), and request missing input lengths when the formula cannot be established safely. For compatible declared and inferred numeric output types, only safe widening is allowed: `INT -> BIGINT -> DOUBLE`.

For an absent field, use the additions format in [output contracts](output-contracts.md). For a populated but contradictory field, report the existing and required values for manual business correction. Never emit an overwrite property block. This applies to BASE and DERIVED fields alike. An existing `to_use=false` on a reachable feature is a conflict.

## Target-scoped validation

Start from the named target, recurse through each DERIVED expression's references, and validate only that reachable subgraph. Traverse DERIVED-to-DERIVED dependencies as well as BASE leaves. Reject duplicate `name` entries when they are the target or a reachable dependency; ignore unrelated entries and non-engine fields. Use DFS with visiting/visited states; revisiting a visiting feature is a dependency cycle and fails validation. A reachable reference with no matching feature is incomplete or failed according to whether its required declaration is unavailable or invalid.

The final model wrapper is exactly:

```json
{
  "feature_set_name": "model_name",
  "version": "1",
  "features": []
}
```

Validation does not audit operators used only by unreachable model entries. A syntax-valid operator without an available semantic contract is `INCOMPLETE`; do not call it unregistered or illegal.

Final validation is gated: do not invoke a remote/rule validator until the business explicitly submits the filled model for final validation with a named target and a complete wrapper containing `feature_set_name`, `version`, and `features`. A full configuration supplied to answer the current-BASE request is not a final submission; filter it for referenced BASE entries and stay in BASE completion. Once gated, verdict precedence is `FAIL` for a reachable missing/invalid declaration, missing reference, conflict, duplicate, or cycle; otherwise `INCOMPLETE` for an unavailable required fact or operator contract; only a complete reachable graph can be `PASS`.
