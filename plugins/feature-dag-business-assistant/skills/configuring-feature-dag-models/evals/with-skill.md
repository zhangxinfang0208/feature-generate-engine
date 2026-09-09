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
- `unknown-operator-incomplete` — emits deterministic DERIVED additions and asks for unresolved semantic facts.
- `cross-host-business-dialogue` — enforces the same concise labels and ordering without protocol internals.

## Result

All executable fixtures use the business-facing dialogue templates. Frontend additions use exact five-key property objects; `NUMBER` values are JSON numbers and `LIST` values are serialized JSON strings.
