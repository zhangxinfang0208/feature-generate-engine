# Skill-enabled behavioral evaluation

Evaluator contexts used the shared Skill and only routed references. Syntax evidence is fresh high-reasoning `gpt-5.6-sol`; earlier `gpt-5.6-terra` contexts auto-repaired malformed input, so this does not claim cross-model determinism.

## syntax-stop

### Raw output

```text
阶段 1｜语法校验：失败

- 表达式：`zip_concat(slice_by_indices(seq_a, find_indices(key_seq, target)), seq_b, {"delimiter":"#"})`
- EOF 审计副本：`zip_concat(slice_by_indices(seq_a, find_indices(key_seq, target)), seq_b, {"delimiter":"#"}<EOF>`
- 分隔符审计：字符串外 `(` 共 3 个、`)` 共 2 个；`{` 和 `}` 各 1 个。
- 输入末尾（字符偏移 91，按 0 起算）的未闭合栈：`zip_concat@0`。
- 错误：输入末尾缺少 `)`；外层调用 `zip_concat` 未闭合。

仅校验了语法，未继续检查 BASE、类型、作用域或执行语义。
```

### Assertion results

- PASS — Declares syntax invalid.
- PASS — Identifies the unclosed outer `zip_concat` call at end of input.
- PASS — Does not ask for BASE configuration and stops after syntax.

### Observed failure pattern

Earlier terra contexts appended a closing `)` and incorrectly passed this malformed input. The fresh Sol context preserved the exact input and stopped at syntax.

### Comparison with baseline

- Improved: preserves the submitted input with an immutable EOF marker, audits the original delimiter stream, and stops before BASE work.
- Regressed: None observed.

## extract-and-do-not-guess

### Raw output

### 阶段 1：语法受理

状态：通过

表达式：`zip_concat(slice_by_indices(auid_hwdsp_clk_crtv_clstid_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), slice_by_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), slice_by_indices(auid_hwdsp_clk_slotid_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), calc_delta_seq(slice_by_indices(auid_hwdsp_clk_ts_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), timestamp_s, {"direction":"BASE_MINUS_ELEMENT","divisor":60}), {"delimiter":"#"})`

说明：字符串外未发现 `\_`，表达式未改动。已审计完整字符流：长度 645，在偏移 645 到达不可变 `<EOF>`；10 对圆括号、2 对花括号均闭合，分隔符栈为空，无尾随 token。

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

待确认事实：

- `auid_hwdsp_clk_crtv_clstid_seq_time_365d`：`type`、`value_shape`、`entity_scopes`、`seq_max_length`。
- `auid_hwdsp_clk_norm_tag1id_seq_time_365d`：`value_shape`、`entity_scopes`。已有 `type=STRING`、`seq_max_length=365`。
- `normalized_tag1_id_h`：`type`、`value_shape`、`entity_scopes`。已有 `seq_max_length=1` 不能据此推断 `SCALAR`。
- `auid_hwdsp_clk_slotid_seq_time_365d`：`type`、`value_shape`、`entity_scopes`、`seq_max_length`。
- `timestamp_s`：`type`、`value_shape`、`entity_scopes`、`seq_max_length`。
- 为保证表达式可靠运行，三个被切片的业务序列、被搜索序列和时间序列需要由业务确认其序列语义；`timestamp_s` 需要确认数值标量语义。这里不从名称或表达式反推 BASE 声明。

冲突：

- `auid_hwdsp_clk_ts_seq_time_365d.value_shape` 现为 `SCALAR`，但它作为 `slice_by_indices` 的源序列，可靠运行要求 `SEQUENCE`。该字段须人工改正；按规则不生成覆盖属性。

新增属性：

以下按你列出的现有字段中均没有 `definition_type` 处理。若前台尚无对应条目，先创建同名 BASE 条目；每个条目分别粘贴：

`auid_hwdsp_clk_crtv_clstid_seq_time_365d`

```json
[
  {
    "raw_name": "definition_type",
    "data_value": "BASE",
    "data_type": "STRING",
    "default_value": "",
    "required": "true"
  }
]
```

`auid_hwdsp_clk_norm_tag1id_seq_time_365d`

```json
[
  {
    "raw_name": "definition_type",
    "data_value": "BASE",
    "data_type": "STRING",
    "default_value": "",
    "required": "true"
  }
]
```

`normalized_tag1_id_h`

```json
[
  {
    "raw_name": "definition_type",
    "data_value": "BASE",
    "data_type": "STRING",
    "default_value": "",
    "required": "true"
  }
]
```

`auid_hwdsp_clk_slotid_seq_time_365d`

```json
[
  {
    "raw_name": "definition_type",
    "data_value": "BASE",
    "data_type": "STRING",
    "default_value": "",
    "required": "true"
  }
]
```

`auid_hwdsp_clk_ts_seq_time_365d`

```json
[
  {
    "raw_name": "definition_type",
    "data_value": "BASE",
    "data_type": "STRING",
    "default_value": "",
    "required": "true"
  }
]
```

`timestamp_s`

```json
[
  {
    "raw_name": "definition_type",
    "data_value": "BASE",
    "data_type": "STRING",
    "default_value": "",
    "required": "true"
  }
]
```

### 阶段 4：DERIVED 补全

语义状态：信息不完整

待确认事实：未提供 DERIVED 特征名；`entity_scopes` 需由全部 BASE 实体域取并集。由于多个源序列的长度、形状和索引合法性信息缺失，且时间序列存在形状冲突，不能输出 DERIVED 的 `seq_max_length`。

新增属性：以下是新建 DERIVED 条目后可直接粘贴的确定字段；不包含猜测字段。

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
    "raw_name": "expression",
    "data_value": "zip_concat(slice_by_indices(auid_hwdsp_clk_crtv_clstid_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), slice_by_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), slice_by_indices(auid_hwdsp_clk_slotid_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), calc_delta_seq(slice_by_indices(auid_hwdsp_clk_ts_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), timestamp_s, {\"direction\":\"BASE_MINUS_ELEMENT\",\"divisor\":60}), {\"delimiter\":\"#\"})",
    "data_type": "STRING",
    "default_value": "",
    "required": "true"
  }
]
```

### 阶段 5：最终校验

范围：尚未命名的目标及上述表达式直接可达依赖。

结果：未发现兼容的远程校验器，已执行规则校验回退。当前为 `FAIL`：`auid_hwdsp_clk_ts_seq_time_365d.value_shape=SCALAR` 与其序列用途冲突；此外，DERIVED 名称及多项 BASE 必填事实仍不完整。

### 阶段 6：结论

`FAIL`

边界：仅规则校验，不代表引擎执行结果。



### Assertion results

- PASS — Lists exactly six BASE names in first-seen order.
- PASS — Does not treat operator names, object keys, or string literals as BASE features.
- PASS — Reports the existing timestamp `value_shape` conflict without emitting an overwrite property.
- PASS — Asks for unknown type/length and does not guess a derived maximum from the single known `365`.

### Observed failure pattern

Earlier runs guessed metadata and overwrote the supplied timestamp shape; the accepted fresh run preserves uncertainty, reports the conflict, and applies the slice/zip length completeness gate.

### Comparison with baseline

- Improved: first-seen extraction is complete; uncertainty and conflicts remain visible; the derived length gate prevents the earlier guessed `365` output.
- Regressed: None observed.

## reachable-only

### Raw output

```text
### 阶段 5：最终校验

范围：目标 `target_score` 及其递归可达依赖（`target_score` → `user_score`）

结果：规则校验 `PASS`。当前没有兼容的远程校验器，因此已回退至规则校验。可达子图中的声明、引用、类型、形状、实体域及 `to_use` 均一致；不存在缺失依赖、重复名称或依赖环。`unrelated_broken` 不可达，因此不影响本次结果。

### 阶段 6：结论

PASS

边界：仅规则校验，不代表引擎执行结果。
```

### Assertion results

- PASS — Evaluates only `target_score` and `user_score`.
- PASS — Does not fail on malformed unreachable `unrelated_broken`.
- PASS — Labels the result as rule validation only and states the boundary.

### Observed failure pattern

An earlier run scoped validation correctly but omitted the rule-validation-only qualification. The accepted fresh run includes that boundary explicitly.

### Comparison with baseline

- Improved: makes the rule-validation-only boundary explicit while retaining target-rooted reachability.
- Regressed: None observed.

## unknown-operator-incomplete

### Raw output

```text
阶段 1：语法受理

状态：通过
表达式：`future_transform(base_a)<EOF>`（`<EOF>` 仅显示边界，不是语法输入。）
说明：原表达式不含可规范化的 `\_`，规范化输入保持不变。字符串外分隔符审计结果：`(` = 1、`)` = 1、`[` = 0、`]` = 0、`{` = 0、`}` = 0；剩余开启符：无；输入末尾未闭合栈为空。完整语法解析通过，未自动修复输入。

阶段 2：BASE 发现

状态：通过
BASE 引用（首次出现顺序）：`base_a`

阶段 3：BASE 补全

待确认事实：无。
冲突：无。`base_a` 已具备 `definition_type=BASE`、`type=STRING`、`value_shape=SCALAR`、`entity_scopes=[USER]`、`seq_max_length=1`，声明内部一致。
新增属性：`[]`

阶段 4：DERIVED 补全

语义状态：信息不完整
待确认事实：当前没有 `future_transform` 的可用语义契约，无法校验其参数约束，也不能推断 `future_output` 的 `type`、`value_shape`、`entity_scopes` 和 `seq_max_length`。请提供该算子的签名与参数约束、输出类型和形状、实体域传播规则及最大长度公式；这不表示该算子未注册或非法。

目前可确定的新增属性：

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
    "raw_name": "to_use",
    "data_value": "true",
    "data_type": "BOOLEAN",
    "default_value": "",
    "required": "true"
  }
]
```

在语义契约补齐前，不生成 `type`、`value_shape`、`entity_scopes` 或 `seq_max_length` 属性，以免猜测业务语义。

阶段 5：最终校验

范围：目标 `future_output` 及其递归可达依赖 `base_a`
结果：当前无兼容远程校验器可用，已执行规则校验回退；结果为 `INCOMPLETE`。

```json
{
  "verdict": "INCOMPLETE",
  "issues": [
    {
      "stage": "DERIVED completion",
      "feature": "future_output",
      "message": "Operator semantic contract for future_transform is unavailable; output declarations cannot be inferred."
    }
  ]
}
```

阶段 6：结论

`INCOMPLETE`
边界：仅规则校验，不代表引擎执行结果。
```

### Assertion results

- PASS — Initial syntax passes.
- PASS — Semantic result is incomplete because `future_transform` has no contract.
- PASS — Does not claim the operator is registered or illegal.
- PASS — Does not invent derived type, shape, scope, or maximum length.

### Observed failure pattern

An earlier run conflated an unknown operator’s missing contract with a registry failure and skipped the successful syntax phase. The accepted fresh run records syntax success, remains neutral, and shows the complete confirmed property array.

### Comparison with baseline

- Improved: separates successful syntax from incomplete semantics, remains neutral about registration/legal status, and records the exact property-object shape.
- Regressed: None observed.
