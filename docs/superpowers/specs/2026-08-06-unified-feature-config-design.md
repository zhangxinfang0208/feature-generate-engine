# Unified Feature Configuration Design

## Goal

Replace the split `features` and `derivedFeatures` JSON structure with one `features` array that represents both base and derived features. The new format is the only supported top-level format. Historical entries whose `definition_type` is null, blank, or absent are treated as base features.

## Configuration Model

`FeatureSetConfig` contains one `features` list. Every list item uses a unified configuration type with the existing shared business fields plus these DAG fields:

- `definition_type`: `BASE` or `DERIVED`; null, blank, or absent means `BASE`.
- `expression`: required and non-blank for `DERIVED`; absent for `BASE`.
- `entity_scopes`: zero or more of `ITEM`, `USER`, and `SCENE`.
- `value_shape`: optional `SCALAR`, `SEQUENCE`, or `VECTOR`.
- `output_policy`: `OUTPUT` or `INTERNAL_ONLY`.

Unknown item-level business fields continue to be retained in `additionalProperties` so fields such as `catalog`, `encode`, and `feature_type` remain loadable without becoming engine concerns.

The obsolete top-level `derivedFeatures` property is rejected with an explicit configuration error. It must not be silently ignored or mapped into the unified list.

## Mapping Rules

The mapper iterates the unified list in declaration order and dispatches by normalized `definition_type`.

For a `BASE` feature:

- `raw_name` is required and becomes the source binding.
- A non-blank `expression` is invalid.
- `output_policy` does not make a base feature a requested DAG target; its effective policy remains `OUTPUT` for the internal definition model.
- Configured `entity_scopes` determine source scope. Existing scope overrides still take precedence. If no scope is available, the current compatibility behavior remains: use `USER`, and report the feature as unresolved during online initialization.
- A configured `value_shape` determines source-node shape. If absent, shape is inferred from `type` as today (`EVENT_SEQUENCE` to `SEQUENCE`, `OBJECT` to `OBJECT`, otherwise `SCALAR`).

For a `DERIVED` feature:

- `expression` is required and non-blank.
- `raw_name` is not used as a source binding.
- Missing or blank `output_policy` defaults to `OUTPUT`; otherwise only `OUTPUT` and `INTERNAL_ONLY` are accepted.
- Type, entity scope, and value shape continue to be inferred from the expression and operators.
- A non-empty configured `entity_scopes` set is a constraint: it must exactly equal the inferred set or DAG construction fails with the feature name and both values.
- A configured `value_shape` is a constraint: it must equal the inferred shape or DAG construction fails with the feature name and both values.

Configuration `VECTOR` maps to the existing logical `ValueShape.CANDIDATE_VECTOR`. `SCALAR` and `SEQUENCE` map directly. Existing internal shapes such as `OBJECT` and `INDEX` remain valid inference results but cannot be explicitly declared by this configuration contract.

## DAG and API Behavior

`FeatureDefinition` gains an optional declared value-shape field. Base source creation uses this field when present. Derived output construction validates declared shape and declared scopes against the producer node after expression inference.

Target selection remains derived-only. With no explicitly requested targets, every enabled derived feature whose effective output policy is `OUTPUT` is selected. An explicit request for a base feature, disabled feature, or `INTERNAL_ONLY` feature remains invalid.

Disabled-reference validation, output ordering, output `store_name` fallback and uniqueness checks, online/offline execution, and public generation results keep their current semantics. All of these operations use the single unified declaration order.

## Validation and Errors

Initialization produces direct errors for:

- a top-level `derivedFeatures` property;
- an invalid `definition_type`, entity scope, value shape, or output policy;
- a duplicate feature name anywhere in the unified list;
- a base feature with a non-blank expression;
- a derived feature without an expression;
- a base feature without a source `raw_name`;
- a derived declared scope or shape that disagrees with inference.

The field path in mapping errors uses `features[]` for both kinds of feature.

## Compatibility Boundary

Supported compatibility is limited to historical base-feature entries inside `features`: absent, null, or blank `definition_type` means `BASE`, and absent `value_shape` retains existing type-based inference. The old split top-level `derivedFeatures` format is intentionally unsupported.

## Testing and Documentation

The dependency-free self-test will cover:

- mixed BASE and DERIVED declarations in one `features` list;
- absent, null, and blank `definition_type` mapping to BASE;
- parsing and mapping `SCALAR`, `SEQUENCE`, and `VECTOR`;
- matching and mismatching derived shape constraints;
- matching and mismatching derived scope constraints;
- rejection of obsolete top-level `derivedFeatures`;
- preservation of unknown business fields;
- target selection, internal-only dependencies, output order, and end-to-end public API generation using the unified format.

The README and runnable demo will show only the unified configuration format.
