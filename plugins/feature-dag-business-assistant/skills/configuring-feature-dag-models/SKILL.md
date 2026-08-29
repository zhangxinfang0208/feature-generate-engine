---
name: configuring-feature-dag-models
description: Use when business users are preparing Feature DAG expressions, BASE feature metadata, DERIVED feature properties, or a final model feature-set JSON for rule validation.
---

# Configuring Feature DAG Models

Use this instruction-only workflow for business configuration. It performs rule validation, never invents missing feature semantics, and does not imply engine execution.

1. **Syntax intake — read [expression grammar](references/expression-grammar.md).** Take one expression and the business-provided DERIVED name. Preserve and show `原始输入` and `规范化输入` separately; if no string-external `\_` exists, they must be identical. Normalize eligible `\_`, report that notice, then parse syntax only. Audit the exact normalized character stream with an immutable `<EOF>` boundary and delimiter stack; keep `<EOF>` out of the expression and never append or repair tokens. On an error, report its location and stop: do not request BASE metadata.
2. **BASE discovery — read [expression grammar](references/expression-grammar.md).** After successful syntax, return only ordered, first-seen unique feature references from the AST; do not treat operators, argument names, object keys, or strings as features.
3. **BASE completion — read [feature fields](references/feature-fields.md).** Request the current entry for every discovered BASE. Ask only for unresolved facts. Emit frontend additions only for absent fields; report existing conflicts for business correction without an overwrite block.
4. **DERIVED completion — read [operator contracts](references/operator-contracts.md).** Only after syntax succeeds and semantic inference is needed, apply available contracts. Use `DERIVED` as the definition type for derived entries. Emit every absent field that the contract determines exactly—even during final validation before reporting `FAIL`; for example, `add(...)` fixes scalar `seq_max_length` to numeric `1`, so emit that `NUMBER` addition without quotes. Ask the business only when the contract or inputs leave the value unresolved. A missing contract is `INCOMPLETE`, not a claim about registration or legality.
5. **Final validation — read [validator extension](references/validator-extension.md).** Enter this stage only after the business provides a named target and complete `{feature_set_name, version, features}` wrapper. Validate the named target and recursively reachable dependencies only. Prefer a compatible available validator; clearly disclose rule-validation fallback. Missing wrapper information stays in completion and must not receive a fabricated validator result.
6. **Verdict — read [output contracts](references/output-contracts.md).** For a gated wrapper, return `FAIL` for reachable declaration/reference/conflict/duplicate/cycle errors before considering `INCOMPLETE`; use `INCOMPLETE` for unavailable facts/contracts, and `PASS` only for a complete reachable graph. A `PASS` is explicitly rule-validation-only; unrelated model entries and non-engine fields are out of scope.

Use [feature fields](references/feature-fields.md) whenever a stage compares declarations, scopes, shapes, defaults, or reachability. Use [output contracts](references/output-contracts.md) whenever emitting additions or a model wrapper. Object-valued additions are `信息不完整` rather than guessed or malformed JSON.
