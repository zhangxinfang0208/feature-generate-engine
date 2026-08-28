---
name: configuring-feature-dag-models
description: Use when business users are preparing Feature DAG expressions, BASE feature metadata, DERIVED feature properties, or a final model feature-set JSON for rule validation.
---

# Configuring Feature DAG Models

Use this instruction-only workflow for business configuration. It performs rule validation, never invents missing feature semantics, and does not imply engine execution.

1. **Syntax intake — read [expression grammar](references/expression-grammar.md).** Take one expression and the business-provided DERIVED name. Normalize eligible `\_`, report that notice, then parse syntax only. On an error, report its location and stop: do not request BASE metadata.
2. **BASE discovery — read [expression grammar](references/expression-grammar.md).** After successful syntax, return only ordered, first-seen unique feature references from the AST; do not treat operators, argument names, object keys, or strings as features.
3. **BASE completion — read [feature fields](references/feature-fields.md).** Request the current entry for every discovered BASE. Ask only for unresolved facts. Emit frontend additions only for absent fields; report existing conflicts for business correction without an overwrite block.
4. **DERIVED completion — read [operator contracts](references/operator-contracts.md).** Only after syntax succeeds and semantic inference is needed, apply available contracts. Ask for an unknown maximum length and emit only absent fields. A missing contract is `INCOMPLETE`, not a claim about registration or legality.
5. **Final validation — read [validator extension](references/validator-extension.md).** Validate the named target and recursively reachable dependencies only. Prefer a compatible available validator; clearly disclose rule-validation fallback.
6. **Verdict — read [output contracts](references/output-contracts.md).** Return `PASS`, `FAIL`, or `INCOMPLETE` in the required Chinese phase format. A `PASS` is explicitly rule-validation-only; unrelated model entries and non-engine fields are out of scope.

Use [feature fields](references/feature-fields.md) whenever a stage compares declarations, scopes, shapes, defaults, or reachability. Use [output contracts](references/output-contracts.md) whenever emitting additions or a model wrapper. Object-valued additions are `信息不完整` rather than guessed or malformed JSON.
