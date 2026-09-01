# With-skill evaluation

The current representative business outputs are stored beside this report and validated by `validate-business-dialogue.mjs`.

## Covered cases

- `missing-expression-entry` — asks only for the expression and gives one editable reply example.
- `syntax-stop` — reports the unclosed call and stops before BASE work.
- `base-config-before-facts` — requests current frontend BASE configuration before individual facts.
- `assignment-shorthand` — reads the DERIVED name from `name=expression` and preserves BASE order.
- `base-facts-after-config` — filters a full model by exact BASE `name` and asks only unresolved facts.
- `extract-and-do-not-guess` — requests only missing BASE entries, including `timestamp_s`, without guessing.
- `reachable-only` — ignores unrelated entries and emits fixed reachable additions.
- `sequence-add-contract` — infers sequence shape and length instead of applying the scalar `add` default.
- `append-contract` — infers the appended element type, scope union, and summed maximum length.
- `join-contract` — folds a sequence to `STRING` / `SCALAR` with maximum length `1`.
- `sequence-discrete-log-contract` — preserves sequence shape through nested `discrete` and `log_base` calls.
- `unknown-operator-incomplete` — emits deterministic DERIVED additions and asks for unresolved semantic facts.
- `cross-host-business-dialogue` — enforces the same concise labels and ordering without protocol internals.

## Result

All executable fixtures use the business-facing dialogue templates. Frontend additions use exact five-key property objects; `NUMBER` values are JSON numbers and `LIST` values are serialized JSON strings. Operator-contract fixtures cover scalar and sequence inference for the current 23-operator standard registry.
