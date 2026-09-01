# Output Contracts

Every response uses [the business dialogue templates](business-dialogue.md). This file defines the model wrapper and frontend property payloads placed in those concise messages. Preserve the submitted expression and its normalized parsing copy internally. Without string-external `\_`, they are byte-for-byte identical. `<EOF>` may appear only in a syntax error explanation, never inside an expression. Syntax failure does not continue BASE or final validation and never repairs the expression.

## Final validation gate and verdict precedence

Final validation starts only when the business supplied both a named target and the complete wrapper below. Without either, use the applicable short dialogue request and do not report remote or rule-validator PASS/FAIL.

```json
{
  "feature_set_name": "...",
  "version": "...",
  "features": []
}
```

For a gated wrapper, inspect only the target's reachable subgraph. Verdict precedence is: `FAIL` for any reachable missing/invalid declaration, missing reference, conflict, duplicate definition, or cycle; otherwise `INCOMPLETE` for an unavailable required fact or operator contract; `PASS` only when the reachable graph is complete and consistent. Before returning `FAIL`, still emit additions for missing fields whose values are fixed by a known contract. In particular, `add(scalar_a, scalar_b)` produces scalar `seq_max_length=1`, while a sequence input makes `add` produce a sequence whose safe length bound comes from the participating sequence inputs. Numeric length additions use an unquoted JSON number. Unreachable malformed entries do not affect the result.

State exactly one validation boundary in the final text: `远程校验结果` when a remote validator returned the result, or `规则校验结果（未调用远程校验器）` when rules were used locally.

## Frontend additions

### Derived length decision

对于包含 `slice_by_indices`、`zip_concat`、序列逐元素数值算子或 `append` 的衍生特征，只有当长度公式需要的可达输入都已完整提供正的 `seq_max_length`、明确的 `value_shape`，且没有类型/形状/实体域冲突时，才允许在新增属性中输出 `seq_max_length`。索引来源也必须满足同样条件；`zip_concat` 和序列逐元素数值算子的多条参与序列必须满足等长约束；`append` 按顺序累加两侧序列长度或标量占用的一个元素。任一必要输入未知或冲突时，将 `seq_max_length` 放入待确认事实并省略该新增属性，不能用某个已知的 `365` 等单项长度猜测结果。

For either BASE or DERIVED missing fields, show one feature heading followed by a JSON array of exact five-key property objects. `default_value` is always `""` and `required` is always `"true"`. The only frontend `data_type` values are `NUMBER`, `LIST`, `STRING`, and `BOOLEAN`. `NUMBER` uses a JSON number; `LIST` uses a JSON string containing the serialized list, so the JSON source for `entity_scopes` is `"[\"USER\"]"` and its parsed `data_value` is the string `["USER"]`; `STRING` uses a JSON string; `BOOLEAN` uses the string `"true"` or `"false"`.

```json
[
    {
      "raw_name": "value_shape",
      "data_value": "SEQUENCE",
      "data_type": "STRING",
      "default_value": "",
      "required": "true"
    },
    {
      "raw_name": "entity_scopes",
      "data_value": "[\"USER\"]",
      "data_type": "LIST",
      "default_value": "",
      "required": "true"
    },
    {
      "raw_name": "seq_max_length",
      "data_value": 365,
      "data_type": "NUMBER",
      "default_value": "",
      "required": "true"
    },
    {
      "raw_name": "to_use",
      "data_value": "true",
      "data_type": "BOOLEAN",
      "default_value": "",
      "required": "true"
    }
]
```

A DERIVED expression is also a STRING property; its JSON string escapes the object-literal quotes:

```json
[
    {
      "raw_name": "expression",
      "data_value": "concat(user_id, {\"delimiter\":\"#\"})",
      "data_type": "STRING",
      "default_value": "",
      "required": "true"
    }
]
```

Do not add absent fields by emitting model feature-object blocks. Do not emit an addition for a conflicting existing field. If an addition would require an object-valued `data_value`, request a supported business representation instead of producing malformed JSON.
