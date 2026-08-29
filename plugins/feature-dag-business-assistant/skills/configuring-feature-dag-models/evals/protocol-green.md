# Deterministic protocol GREEN evidence

All independently returned response objects are preserved verbatim, one object per line, in `protocol-green.raw.jsonl`. Run `node validate-protocol.mjs` from this directory to verify standalone JSON, byte-for-byte repetition consistency, canonical serialization order, fixed questions, stage/verdict invariants, frontend property types, and contextual model-correction identities. The evaluator also mutates valid evidence into six invalid states and requires every mutation to be rejected. The final raw line is an actual Claude Code CLI run with this plugin; it is byte-for-byte identical to the canonical Codex output for the same request. This evaluator is development evidence only; plugin users do not run it.

## Repetition 1

### Raw output

```json
{"protocol_version":"1.0","current_stage":"FINAL_VALIDATION","stages":{"syntax_intake":{"status":"PASS","message":"表达式语法完整。","original_input":"add(user_score, 1)","normalized_input":"add(user_score, 1)","audit":[],"issues":[]},"base_discovery":{"status":"PASS","message":"已按首次出现顺序提取 BASE 引用。","base_references":["user_score"],"issues":[]},"base_completion":{"status":"PASS","message":"BASE 配置完整。","pending_facts":[],"conflicts":[],"additions":[]},"derived_completion":{"status":"PASS","message":"DERIVED 字段可由表达式和算子契约确定。","pending_facts":[],"conflicts":[],"additions":[{"feature_name":"target_score","feature_kind":"DERIVED","properties":[{"raw_name":"definition_type","data_value":"DERIVED","data_type":"STRING","default_value":"","required":"true"},{"raw_name":"expression","data_value":"add(user_score, 1)","data_type":"STRING","default_value":"","required":"true"},{"raw_name":"output_policy","data_value":"OUTPUT","data_type":"STRING","default_value":"","required":"true"},{"raw_name":"type","data_value":"INT","data_type":"STRING","default_value":"","required":"true"},{"raw_name":"value_shape","data_value":"SCALAR","data_type":"STRING","default_value":"","required":"true"},{"raw_name":"entity_scopes","data_value":"[\"USER\"]","data_type":"LIST","default_value":"","required":"true"},{"raw_name":"seq_max_length","data_value":1,"data_type":"NUMBER","default_value":"","required":"true"},{"raw_name":"to_use","data_value":"true","data_type":"BOOLEAN","default_value":"","required":"true"}]}]},"final_validation":{"status":"SKIPPED","message":"尚未满足最终校验闸门。","mode":"NOT_RUN","target_feature":null,"reachable_features":[],"issues":[]},"verdict":{"status":"SKIPPED","value":null,"message":"最终校验尚未执行。","boundary":null}},"next_request":{"request_type":"MODEL_WRAPPER","question":"请提供 target_feature 以及完整的 feature_set_name、version、features 模型包装。","required_features":[],"required_fields":[],"expected_input_format":{"target_feature":"target_score","feature_set":{"feature_set_name":"...","version":"...","features":[]}}}}
```

### Assertion results

- PASS — Exactly one parseable JSON object; no prose or Markdown was emitted by the evaluated agent.
- PASS — Protocol version and canonical top-level/stage key order are present.
- PASS — All six stages are present and final validation/verdict are `SKIPPED` before the wrapper gate.
- PASS — Exactly one `MODEL_WRAPPER` request uses the fixed question verbatim.
- PASS — DERIVED additions are feature-grouped; every property has the exact five keys, numeric `1` is unquoted, and LIST uses the serialized string `["USER"]`.

## Repetitions 2 through 4

Three additional independent fresh-context agents received the same request. Each raw output was byte-for-byte identical to Repetition 1.

### Assertion results

- PASS — All repetitions returned exactly one JSON object with the same canonical key order.
- PASS — All repetitions selected `current_stage=FINAL_VALIDATION` and the same six stage statuses.
- PASS — All repetitions emitted the same grouped DERIVED additions in the same property order.
- PASS — All repetitions selected `MODEL_WRAPPER` and copied the fixed question verbatim.
- PASS — No repetition emitted Markdown, leading/trailing prose, a second question, or an early verdict.

## Repetition 5 before refactor

The fifth independent output kept the complete protocol shape, stage statuses, additions, request type, and fixed question, but changed `expected_input_format.feature_set.version` from the literal placeholder `"..."` to `"1"`.

### Assertion results

- PASS — JSON-only envelope, stage order, property format, and fixed question.
- FAIL — The example input placeholder was not deterministic across agents.

### Refactor

Protocol 1.0 now fixes canonical field order and exact `expected_input_format` objects. For `MODEL_WRAPPER`, a known target name is reused, while `feature_set_name` and `version` must remain the literal `"..."` placeholders. The JSON Schema encodes these request-specific shapes.

## Repetition 5 after refactor

The same agent re-read the refactored protocol and repeated the same request. Its output was byte-for-byte identical to Repetition 1, including `feature_set_name="..."` and `version="..."`.

### Assertion results

- PASS — All deterministic protocol assertions pass after refactor.
- PASS — Five independent repetitions now converge on the same response shape, request, additions, ordering, and placeholders.

## Stage variation: gated FAIL before refactor

A reachable-only final-validation case correctly returned `FAIL`, ignored the unrelated malformed feature, and emitted the deterministic `seq_max_length=1` addition. Because the initial request enum had no correction/resubmission state, it then selected `NONE` with `无需补充信息。` even though the business still needed to apply the addition.

### Assertion results

- PASS — Canonical JSON envelope, six stages, reachable scope, rule-only boundary, FAIL verdict, and five-key numeric addition.
- FAIL — The next request did not tell the business to correct and resubmit the model.

### Refactor

Protocol 1.0 adds `MODEL_CORRECTION` after higher-precedence syntax, conflict, and missing-fact requests. It preserves the supplied target/model identities, lists affected features and fields, and uses one fixed correction question.

## Stage variations after refactor

Fresh-context agents evaluated five distinct workflow states.

### Syntax failure

- PASS — `current_stage=SYNTAX_INTAKE`; the exact malformed input is preserved.
- PASS — All later stages and verdict are present as `SKIPPED`.
- PASS — The only request is `SYNTAX_CORRECTION` with its fixed question and `{"expression":"..."}` input shape.

### Missing BASE metadata

- PASS — `current_stage=BASE_COMPLETION`; BASE references retain first-seen order.
- PASS — `pending_facts`, `required_features`, and `required_fields` use the canonical structures and order.
- PASS — The only request is `BASE_METADATA` with its fixed question and `{"features":[]}` input shape.

### BASE conflict

- PASS — The existing `SCALAR` versus required `SEQUENCE` conflict is reported without an overwrite addition.
- PASS — The only request is `CONFLICT_CORRECTION` with the affected feature and field.

### Unknown operator contract

- PASS — DERIVED completion is `INCOMPLETE` without claiming that the operator is unregistered or illegal.
- PASS — Deterministic DERIVED additions retain the canonical property order.
- PASS — The only request is `OPERATOR_CONTRACT` with its fixed question and empty `fields` object.

### Gated reachable FAIL

- PASS — Only `target_score` and `user_score` are reachable; the unrelated malformed feature is ignored.
- PASS — Rule fallback, FAIL precedence, and `RULE_VALIDATION_ONLY` boundary are explicit.
- PASS — The five-key numeric addition uses unquoted `data_value: 1`.
- PASS — A fresh context selects `MODEL_CORRECTION`, lists `target_score`/`seq_max_length`, and preserves the supplied target, feature-set name, and version in `expected_input_format`.

An already-running context that had read the pre-refactor protocol continued to select its cached `NONE` branch. It is excluded from post-refactor evidence; fresh contexts loaded the new protocol correctly.

### Missing expression entry

- RED — Before the entry rule was promoted to `SKILL.md`, a fresh agent echoed the generic invocation as free text.
- PASS — After refactor, a fresh context returns the complete envelope with `current_stage=SYNTAX_INTAKE`, syntax `PENDING`, JSON `null` expression fields, later stages `SKIPPED`, and the fixed `EXPRESSION_INPUT` request.
