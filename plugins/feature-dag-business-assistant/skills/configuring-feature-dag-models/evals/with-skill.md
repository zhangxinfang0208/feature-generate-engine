# Skill-enabled behavioral evaluation

Final post-review fresh-context evidence. The earlier reachable-only r1 omission is historical RED; r2 contains the minimal deterministic fix.

## syntax-stop

### Raw output

```text
校验结果：**存在语法问题**。

- 原始输入：`zip_concat(slice_by_indices(seq_a, find_indices(key_seq, target)), seq_b, {"delimiter":"#"}`
- 规范化输入：`zip_concat(slice_by_indices(seq_a, find_indices(key_seq, target)), seq_b, {"delimiter":"#"}`

在第 92 个字符位置（`<EOF>`，即输入末尾）仍有一个未闭合的 `(`，它对应 `zip_concat` 后第 11 个字符位置的左括号。因此表达式末尾少一个右括号 `)`。
```

### Assertion results

- PASS — Declares syntax invalid and shows exact original and normalized input.
- PASS — Identifies the unclosed call near the end.
- PASS — Does not ask for BASE configuration or continue to later stages.

### Observed failure pattern

Earlier contexts repaired malformed input by appending `)`. The final context preserved both inputs exactly and stopped at syntax.

### Comparison with baseline

- Improved: no repair, exact input preservation, and no BASE work after syntax failure.
- Regressed: None observed.

## extract-and-do-not-guess

### Raw output

```text
### 阶段 1：语法受理

状态：通过

原始输入：`zip_concat(slice_by_indices(auid_hwdsp_clk_crtv_clstid_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), slice_by_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), slice_by_indices(auid_hwdsp_clk_slotid_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), calc_delta_seq(slice_by_indices(auid_hwdsp_clk_ts_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), timestamp_s, {"direction":"BASE_MINUS_ELEMENT","divisor":60}), {"delimiter":"#"})`

规范化输入：`zip_concat(slice_by_indices(auid_hwdsp_clk_crtv_clstid_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), slice_by_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), slice_by_indices(auid_hwdsp_clk_slotid_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), calc_delta_seq(slice_by_indices(auid_hwdsp_clk_ts_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), timestamp_s, {"direction":"BASE_MINUS_ELEMENT","divisor":60}), {"delimiter":"#"})`

说明：没有字符串外的 `\_`，两份输入完全相同。逐字符扫描到不可变边界 `<EOF>` 时，圆括号、花括号和引号均已闭合，分隔符栈为 `[]`，无尾随 token。

### 阶段 2：BASE 发现

状态：通过

BASE 引用（首次出现顺序）：

1. `auid_hwdsp_clk_crtv_clstid_seq_time_365d`
2. `auid_hwdsp_clk_norm_tag1id_seq_time_365d`
3. `normalized_tag1_id_h`
4. `auid_hwdsp_clk_slotid_seq_time_365d`
5. `auid_hwdsp_clk_ts_seq_time_365d`
6. `timestamp_s`

### 阶段 3：BASE 补全

以下 6 个 BASE 均可新增这两个确定属性：

```json
[
  {
    "raw_name": "definition_type",
    "data_value": "BASE",
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

待确认事实：

- `auid_hwdsp_clk_crtv_clstid_seq_time_365d`：`type`、`value_shape`、`entity_scopes`、`seq_max_length`
- `auid_hwdsp_clk_norm_tag1id_seq_time_365d`：`value_shape`、`entity_scopes`
- `normalized_tag1_id_h`：`type`、`value_shape`、`entity_scopes`
- `auid_hwdsp_clk_slotid_seq_time_365d`：`type`、`value_shape`、`entity_scopes`、`seq_max_length`
- `timestamp_s`：`type`、`value_shape`、`entity_scopes`、`seq_max_length`

冲突：

- `auid_hwdsp_clk_ts_seq_time_365d` 当前为 `value_shape=SCALAR`，但它在 `slice_by_indices` 中作为源序列使用，可靠配置要求 `SEQUENCE`。该字段必须由业务人工修正，不能用新增属性覆盖。

未提供的 BASE 不能根据名称或表达式猜测类型、形状、实体域和长度；其 feature 条目及 `name` 也需要在前台存在。

### 阶段 4：DERIVED 补全

语义状态：信息不完整

DERIVED 名称尚未提供。下面是当前能够确定、可粘贴到该 DERIVED 条目的新增属性：

```json
[
  {
    "raw_name": "definition_type",
    "data_value": "DERIVED",
    "data_type": "STRING",
    "default_value": "",
    "required": "true"
  },
  {
    "raw_name": "expression",
    "data_value": "zip_concat(slice_by_indices(auid_hwdsp_clk_crtv_clstid_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), slice_by_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), slice_by_indices(auid_hwdsp_clk_slotid_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), calc_delta_seq(slice_by_indices(auid_hwdsp_clk_ts_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), timestamp_s, {\"direction\":\"BASE_MINUS_ELEMENT\",\"divisor\":60}), {\"delimiter\":\"#\"})",
    "data_type": "STRING",
    "default_value": "",
    "required": "true"
  },
  {
    "raw_name": "output_policy",
    "data_value": "OUTPUT",
    "data_type": "STRING",
    "default_value": "",
    "required": "true"
  },
  {
    "raw_name": "type",
    "data_value": "STRING",
    "data_type": "STRING",
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

尚不能生成：

- `entity_scopes`：多个可达 BASE 的实体域未知。
- `seq_max_length`：`slice_by_indices` 和 `zip_concat` 的长度闸门未满足，且时间序列 BASE 存在形状冲突，不能用已知的 `365` 猜测。
- DERIVED `name`：这是特征身份，不属于前台属性新增。
- 最终模型包装：仍缺 DERIVED 名称、`feature_set_name`、`version` 和完整 `features`。

因此本轮不进入最终校验，也不产生 `PASS`、`FAIL` 或最终验证结论。

```

### Assertion results

- PASS — Lists exactly six BASE names in first-seen order.
- PASS — Excludes operators, object keys, and string literals.
- PASS — Reports the existing shape conflict without an overwrite property.
- PASS — Asks for unknown type/length instead of guessing.

### Observed failure pattern

Earlier evidence guessed derived length and omitted deterministic BASE/DERIVED additions. The final context preserves unknown facts while emitting only deterministic fields.

### Comparison with baseline

- Improved: exact six-reference extraction, conflict handling, deterministic additions, and no Stage 5 without wrapper.
- Regressed: None observed.

## reachable-only

### Raw output

```text
### 阶段 5：最终校验

范围：目标 `target_score` 及其递归可达依赖，即 `target_score` → `user_score`

校验方式：兼容的远程校验器不可用，已执行规则校验回退。

结果：规则校验失败。

问题：

```json
[
  {
    "stage": "最终校验",
    "feature": "target_score",
    "field": "seq_max_length",
    "message": "可达 DERIVED 特征缺少必填字段 seq_max_length；根据 add 算子契约，该字段应为 1。"
  }
]
```

新增属性：

```json
[
  {
    "raw_name": "seq_max_length",
    "data_value": 1,
    "data_type": "NUMBER",
    "default_value": "",
    "required": "true"
  }
]
```

`add(user_score, 1)` 推断为 `INT` / `SCALAR`、实体域 `USER`、`seq_max_length=1`，其余已有声明一致。`unrelated_broken` 不在目标的递归可达子图内，即使其表达式格式错误，也不影响本次目标校验。

### 阶段 6：结论

FAIL

边界：仅规则校验，不代表引擎执行结果。
```

### Assertion results

- PASS — Evaluates only target_score and user_score.
- PASS — Does not fail because unrelated_broken is malformed and unreachable.
- PASS — Reports FAIL for the missing reachable target field.
- PASS — Emits the exact five-key NUMBER addition with unquoted numeric data_value 1.
- PASS — Labels the result as rule validation only.

### Observed failure pattern

Post-review r1 omitted the deterministic length addition. Post-review r2 applies the minimal `add` contract fix while retaining FAIL for the incomplete declaration.

### Comparison with baseline

- Improved: target-rooted reachability, unrelated-entry isolation, fixed numeric addition, and correct FAIL precedence.
- Regressed: None observed.

## unknown-operator-incomplete

### Raw output

```text
### 阶段 1：语法受理

状态：通过
原始输入：`future_transform(base_a)`
规范化输入：`future_transform(base_a)`
说明：未发现字符串外的 `\_`，两份输入完全相同。分隔符审计：在偏移 16 处压入 `(`，在偏移 23 处由 `)` 闭合；偏移 24 到达不可变边界 `<EOF>` 时栈为空，无尾随字符。

### 阶段 2：BASE 发现

状态：通过
BASE 引用（首次出现顺序）：`base_a`

### 阶段 3：BASE 补全

待确认事实：无。
冲突：无。
新增属性：`base_a` 尚未提供可达 BASE 必需的 `to_use=true`：

```json
[
  {
    "raw_name": "to_use",
    "data_value": "true",
    "data_type": "BOOLEAN",
    "default_value": "",
    "required": "true"
  }
]
```

### 阶段 4：DERIVED 补全

语义状态：信息不完整
说明：`future_transform` 没有可用的语义契约。这不表示该算子未注册或非法，但目前无法推断 `future_output` 的输出声明。
待确认事实：请提供 `future_transform` 的语义契约，或直接确认 `future_output` 的 `type`、`value_shape`、`entity_scopes` 和 `seq_max_length`。

`future_output` 当前可确定的新增属性为：

```json
[
  {
    "raw_name": "definition_type",
    "data_value": "DERIVED",
    "data_type": "STRING",
    "default_value": "",
    "required": "true"
  },
  {
    "raw_name": "expression",
    "data_value": "future_transform(base_a)",
    "data_type": "STRING",
    "default_value": "",
    "required": "true"
  },
  {
    "raw_name": "output_policy",
    "data_value": "OUTPUT",
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

`name=future_output` 是特征标识，不作为新增属性输出。由于缺少完整模型包装，本轮不进入最终校验阶段。
```

### Assertion results

- PASS — Initial syntax passes with exact original/normalized input.
- PASS — Semantic result is incomplete because future_transform has no contract.
- PASS — Does not claim the operator is registered or illegal.
- PASS — Adds only deterministic output_policy=OUTPUT and to_use=true plus identity/expression; does not invent type, shape, scope, or length.
- PASS — Does not enter Stage 5 without a complete wrapper.

### Observed failure pattern

Earlier evidence omitted deterministic DERIVED fields or treated an unknown operator as illegal. The final context reports INCOMPLETE and asks only for unresolved semantics.

### Comparison with baseline

- Improved: deterministic additions, explicit unknown-contract boundary, and completion-stage stop.
- Regressed: None observed.
