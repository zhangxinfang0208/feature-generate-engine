# Business dialogue GREEN evidence

Raw representative outputs are stored in the adjacent `business-dialogue-green-*.txt` files and checked by `validate-business-dialogue.mjs`.

## Current BASE configuration request

- Five independent Claude Code runs produced byte-for-byte identical concise output.
- An independent Codex agent produced the same output.
- The response asks for current frontend configuration before any manual field values.
- The reply example does not prefill engine-required `type` or shape fields.

## Full configuration filtering

- Five independent Claude Code runs produced byte-for-byte identical output.
- An independent Codex agent produced the same output.
- A full configuration containing `feature_set_name`, `version`, an unrelated malformed DERIVED entry, one partial referenced BASE, and one complete referenced BASE remained in BASE completion.
- Only the partial referenced BASE appeared in the missing-facts request; the unrelated and complete entries were ignored.

## Additional variations

- `derived_name=expression` shorthand preserved the DERIVED name and extracted BASE references from the right-hand expression.
- A missing outer parenthesis stopped before BASE work.
- Reachable-only final validation ignored an unrelated malformed feature and emitted numeric `seq_max_length=1` in the five-key frontend format.
- A missing operator contract requested unresolved DERIVED facts without inventing semantics or an early verdict.
- The approved long expression retained `timestamp_s` as a BASE reference and requested exactly the three absent BASE entries in AST order.
