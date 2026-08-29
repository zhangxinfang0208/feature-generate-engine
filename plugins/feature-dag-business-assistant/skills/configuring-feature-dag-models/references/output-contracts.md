# Output Contracts

Use these Chinese phase templates in order; omit future phases after a syntax failure. A syntax success must include the unchanged normalized input plus delimiter audit evidence; a syntax failure must identify the residual opener(s) before `<EOF>`.

```text
### 阶段 1：语法受理
状态：通过 | 失败
表达式：`...`
说明：...（失败时包含偏移位置，并注明“本轮不进入 BASE 发现或元数据阶段”。）

### 阶段 2：BASE 发现
状态：通过
BASE 引用（首次出现顺序）：`...`

### 阶段 3：BASE 补全
待确认事实：...
冲突：...（仅人工业务修正；不输出覆盖属性。）
新增属性：...

### 阶段 4：DERIVED 补全
语义状态：通过 | 信息不完整
待确认事实：...
新增属性：...

### 阶段 5：最终校验
范围：目标 `...` 及其递归可达依赖
结果：...

### 阶段 6：结论
PASS | FAIL | INCOMPLETE
边界：仅规则校验，不代表引擎执行结果。
```

## Frontend additions

### Derived length decision

对于包含 `slice_by_indices` 或 `zip_concat` 的衍生特征，只有当所有决定源序列长度、索引长度与索引合法性的可达输入都已完整提供正的 `seq_max_length`、明确的 `value_shape`，且没有类型/形状/实体域冲突时，才允许在新增属性中输出 `seq_max_length`。索引来源也必须满足同样条件；`zip_concat` 的所有参与序列必须满足等长约束。任一输入未知或冲突时，将 `seq_max_length` 放入待确认事实并省略该新增属性，不能用某个已知的 `365` 等单项长度猜测结果。

For either BASE or DERIVED missing fields, `新增属性` is an array of exact five-key property objects. `default_value` is always `""` and `required` is always `"true"`. The only frontend `data_type` values are `NUMBER`, `LIST`, `STRING`, and `BOOLEAN`. `NUMBER` uses a JSON number; `LIST` uses a JSON string containing the serialized list, so the JSON source for `entity_scopes` is `"[\"USER\"]"` and its parsed `data_value` is the string `["USER"]`; `STRING` uses a JSON string; `BOOLEAN` uses the string `"true"` or `"false"`.

```json
[
  {
    "raw_name": "seq_max_length",
    "data_value": 365,
    "data_type": "NUMBER",
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
    "raw_name": "value_shape",
    "data_value": "SEQUENCE",
    "data_type": "STRING",
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

Do not add absent fields by emitting feature-object blocks. Do not emit an addition for a conflicting existing field. If an addition would require an object-valued `data_value`, return `信息不完整` and request a supported business representation instead of producing malformed JSON.
