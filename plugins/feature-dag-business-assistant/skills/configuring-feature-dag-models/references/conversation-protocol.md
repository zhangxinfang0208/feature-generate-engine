# Deterministic Conversation Protocol

This protocol is the mandatory user-facing interface on every host. Content changes with the request; structure and question wording do not.

## Canonical JSON envelope

Return one parseable JSON object with these keys in this order. Do not wrap it in a Markdown code fence and do not write text before or after it.

```json
{
  "protocol_version": "1.0",
  "current_stage": "BASE_COMPLETION",
  "stages": {
    "syntax_intake": {
      "status": "PASS",
      "message": "表达式语法完整。",
      "original_input": "add(user_score, 1)",
      "normalized_input": "add(user_score, 1)",
      "audit": [],
      "issues": []
    },
    "base_discovery": {
      "status": "PASS",
      "message": "已按首次出现顺序提取 BASE 引用。",
      "base_references": ["user_score"],
      "issues": []
    },
    "base_completion": {
      "status": "PENDING",
      "message": "需要业务提供 BASE 当前配置。",
      "pending_facts": [
        {
          "feature_name": "user_score",
          "fields": ["definition_type", "type", "value_shape", "entity_scopes", "seq_max_length"],
          "reason": "BASE 字段不能从名称或表达式猜测。"
        }
      ],
      "conflicts": [],
      "additions": []
    },
    "derived_completion": {
      "status": "SKIPPED",
      "message": "等待 BASE 补全。",
      "pending_facts": [],
      "conflicts": [],
      "additions": []
    },
    "final_validation": {
      "status": "SKIPPED",
      "message": "尚未满足最终校验闸门。",
      "mode": "NOT_RUN",
      "target_feature": null,
      "reachable_features": [],
      "issues": []
    },
    "verdict": {
      "status": "SKIPPED",
      "value": null,
      "message": "最终校验尚未执行。",
      "boundary": null
    }
  },
  "next_request": {
    "request_type": "BASE_METADATA",
    "question": "请按 expected_input_format 提供 required_features 中 BASE 特征的当前配置。",
    "required_features": ["user_score"],
    "required_fields": ["definition_type", "type", "value_shape", "entity_scopes", "seq_max_length"],
    "expected_input_format": {
      "features": []
    }
  }
}
```

All six stage objects are always present in this order. Every object keeps every shown key; use JSON `null`, `[]`, or `SKIPPED` instead of removing a key. `issues`, `pending_facts`, `conflicts`, and `additions` use the shapes defined below. [conversation-protocol.schema.json](conversation-protocol.schema.json) enforces members, types, request-specific input shapes, and core stage/verdict combinations. JSON Schema treats object keys as unordered; canonical serialization order is an additional protocol rule checked by the evaluation validator, not by JSON Schema. AST order, reachable-graph correctness, and preservation of supplied model identities are semantic input/output checks and are likewise outside standalone output-schema validation.

## Stage and verdict values

- `current_stage`: `SYNTAX_INTAKE`, `BASE_DISCOVERY`, `BASE_COMPLETION`, `DERIVED_COMPLETION`, `FINAL_VALIDATION`, or `VERDICT`.
- Stage `status`: `PASS`, `FAIL`, `PENDING`, `INCOMPLETE`, or `SKIPPED`.
- `final_validation.mode`: `NOT_RUN`, `REMOTE`, or `RULE_FALLBACK`.
- Final `verdict.value`: `PASS`, `FAIL`, `INCOMPLETE`, or JSON `null` before the final-validation gate.
- `verdict.boundary`: `REMOTE_VALIDATOR_RESULT`, `RULE_VALIDATION_ONLY`, or JSON `null` when not run.

An issue always has all five keys:

```json
{
  "code": "MISSING_FIELD",
  "feature_name": "target_score",
  "field": "seq_max_length",
  "offset": null,
  "message": "可达 DERIVED 特征缺少 seq_max_length。"
}
```

A conflict always has all five keys:

```json
{
  "feature_name": "event_ts_seq",
  "field": "value_shape",
  "existing_value": "SCALAR",
  "required_value": "SEQUENCE",
  "message": "请业务人工修正；不输出覆盖属性。"
}
```

An addition group always identifies the feature and contains only five-key frontend property objects:

```json
{
  "feature_name": "target_score",
  "feature_kind": "DERIVED",
  "properties": [
    {
      "raw_name": "seq_max_length",
      "data_value": 1,
      "data_type": "NUMBER",
      "default_value": "",
      "required": "true"
    }
  ]
}
```

## Deterministic next request

Choose the first applicable request in this precedence. Emit exactly one request and copy its `question` verbatim.

| Precedence | `request_type` | Exact `question` |
|---:|---|---|
| 1 | `EXPRESSION_INPUT` | `请按 expected_input_format 提供一个完整的特征表达式。` |
| 2 | `SYNTAX_CORRECTION` | `请修正表达式语法后重新提交完整表达式；系统不会自动补写或修复。` |
| 3 | `CONFLICT_CORRECTION` | `请业务人工修正 conflicts 中的冲突，并按 expected_input_format 返回修正后的特征配置。` |
| 4 | `BASE_METADATA` | `请按 expected_input_format 提供 required_features 中 BASE 特征的当前配置。` |
| 5 | `DERIVED_NAME` | `请提供当前表达式对应的 DERIVED 特征名称。` |
| 6 | `OPERATOR_CONTRACT` | `请提供缺失算子的语义契约，或直接确认 DERIVED 输出字段。` |
| 7 | `DERIVED_METADATA` | `请按 pending_facts 确认 DERIVED 特征中无法由算子契约确定的字段。` |
| 8 | `MODEL_CORRECTION` | `请按 issues 修正模型并应用 additions（如有），然后按 expected_input_format 重新提交 target_feature 与完整模型包装。` |
| 9 | `MODEL_WRAPPER` | `请提供 target_feature 以及完整的 feature_set_name、version、features 模型包装。` |
| 10 | `NONE` | `无需补充信息。` |

Populate `required_features` in AST first-seen order. Populate `required_fields` by filtering this canonical order to the applicable fields: `definition_type`, `expression`, `output_policy`, `type`, `value_shape`, `entity_scopes`, `seq_max_length`, `to_use`. Addition properties use the same canonical field order. `expected_input_format` is always exactly one of these objects; literal `"..."` placeholders must not be replaced by guessed sample values:

- `EXPRESSION_INPUT` or `SYNTAX_CORRECTION`: `{"expression":"..."}`.
- BASE or conflict correction: `{"features":[]}`.
- `DERIVED_NAME`: `{"derived_feature_name":"..."}`.
- operator or DERIVED facts: `{"feature_name":"<known feature name or ...>","fields":{}}`; use the known DERIVED name when available, otherwise the literal `"..."`.
- `MODEL_CORRECTION`: `{"target_feature":"<supplied target>","feature_set":{"feature_set_name":"<supplied name>","version":"<supplied version>","features":[]}}`; preserve the three supplied identity values exactly and leave `features` empty as the shape placeholder. Put affected feature names and fields in `required_features` and `required_fields`.
- `MODEL_WRAPPER`: `{"target_feature":"<known feature name or ...>","feature_set":{"feature_set_name":"...","version":"...","features":[]}}`; use the known target name when available, but always keep `feature_set_name` and `version` as the literal `"..."` placeholders.
- `NONE`: `{}`.

## Stage transitions

- Missing expression: `current_stage=SYNTAX_INTAKE`; syntax is `PENDING` with both expression fields JSON `null`; all later stages and verdict are `SKIPPED`; request expression input.
- Syntax failure: `current_stage=SYNTAX_INTAKE`; syntax is `FAIL`; all later stages and verdict are `SKIPPED`; request syntax correction.
- Syntax success: complete BASE discovery, then stop at the first unresolved completion stage according to request precedence.
- A conflict is reported without an overwrite addition and takes precedence over other metadata questions.
- Missing operator semantics makes DERIVED completion `INCOMPLETE`; it is not proof that the operator is unregistered or illegal.
- Final validation runs only with a named target and complete wrapper. Before that gate, verdict stays `SKIPPED` with `value=null`.
- After gated validation, `current_stage=VERDICT`, final validation has the applicable status, and verdict follows `FAIL` before `INCOMPLETE` before `PASS`. A `FAIL` that is not already routed to a higher-precedence fact or conflict request uses `MODEL_CORRECTION`; only a completed `PASS` uses `NONE`.

## Common mistakes

- Omitting later stage objects instead of marking them `SKIPPED`.
- Adding headings, explanations, or Markdown fences outside the JSON.
- Rephrasing the fixed question or asking multiple questions.
- Returning final `INCOMPLETE` before the final-validation gate.
- Putting raw five-key properties directly in `additions` without their feature group.
- Serializing `NUMBER` as a string or `LIST` as a JSON array instead of the required serialized-list string.
