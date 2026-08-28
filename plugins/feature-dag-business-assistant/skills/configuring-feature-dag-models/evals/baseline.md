## syntax-stop

### Raw output

该表达式括号配对完整，没有少括号或多括号等语法问题：

`zip_concat(slice_by_indices(seq_a, find_indices(key_seq, target)), seq_b, {"delimiter":"#"})`

仅从语法看是有效的。

### Assertion results

- FAIL — The response declares the expression syntactically valid instead of invalid.
- FAIL — The response says all parentheses are paired and does not identify the unclosed call near the end.
- PASS — The response does not ask for BASE configuration.

### Observed failure pattern

It accepts a malformed expression as syntactically valid and stops no work before metadata configuration.

## extract-and-do-not-guess

### Raw output

```json
{
  "feature_name": "auid_hwdsp_clk_norm_tag1id_history_zip_seq_time_365d",
  "definition_type": "DERIVED",
  "type": "STRING",
  "value_shape": "SEQUENCE",
  "entity_scopes": ["USER"],
  "seq_max_length": 365,
  "expression": "zip_concat(slice_by_indices(auid_hwdsp_clk_crtv_clstid_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), slice_by_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), slice_by_indices(auid_hwdsp_clk_slotid_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), calc_delta_seq(slice_by_indices(auid_hwdsp_clk_ts_seq_time_365d, find_indices(auid_hwdsp_clk_norm_tag1id_seq_time_365d, normalized_tag1_id_h)), timestamp_s, {"direction":"BASE_MINUS_ELEMENT","divisor":60}), {"delimiter":"#"})"
}
```

同时补齐/修正以下 BASE 配置：

```json
[
  {
    "feature_name": "auid_hwdsp_clk_norm_tag1id_seq_time_365d",
    "definition_type": "BASE",
    "type": "STRING",
    "value_shape": "SEQUENCE",
    "entity_scopes": ["USER"],
    "seq_max_length": 365
  },
  {
    "feature_name": "normalized_tag1_id_h",
    "definition_type": "BASE",
    "type": "STRING",
    "value_shape": "SCALAR",
    "entity_scopes": ["USER"],
    "seq_max_length": 1
  },
  {
    "feature_name": "auid_hwdsp_clk_ts_seq_time_365d",
    "definition_type": "BASE",
    "type": "BIGINT",
    "value_shape": "SEQUENCE",
    "entity_scopes": ["USER"],
    "seq_max_length": 365
  },
  {
    "feature_name": "auid_hwdsp_clk_crtv_clstid_seq_time_365d",
    "definition_type": "BASE",
    "type": "BIGINT",
    "value_shape": "SEQUENCE",
    "entity_scopes": ["USER"],
    "seq_max_length": 365
  },
  {
    "feature_name": "auid_hwdsp_clk_slotid_seq_time_365d",
    "definition_type": "BASE",
    "type": "BIGINT",
    "value_shape": "SEQUENCE",
    "entity_scopes": ["USER"],
    "seq_max_length": 365
  },
  {
    "feature_name": "timestamp_s",
    "definition_type": "BASE",
    "type": "BIGINT",
    "value_shape": "SCALAR",
    "entity_scopes": ["USER"],
    "seq_max_length": 1
  }
]
```

关键修正：`auid_hwdsp_clk_ts_seq_time_365d` 必须是 `SEQUENCE`，否则不能传入 `slice_by_indices`。

### Assertion results

- FAIL — The listed BASE candidates begin with `auid_hwdsp_clk_norm_tag1id_seq_time_365d` rather than the first-seen `auid_hwdsp_clk_crtv_clstid_seq_time_365d`.
- PASS — The emitted feature names do not include operator names, object keys, or string literals as BASE features.
- FAIL — The response overwrites the supplied `auid_hwdsp_clk_ts_seq_time_365d` `value_shape=SCALAR` with `SEQUENCE` instead of reporting the conflict.
- FAIL — The response guesses absent types, shapes, scopes, and lengths rather than asking for the unknown metadata.

### Observed failure pattern

It fabricates immediately copyable BASE properties, including overwriting contradictory metadata, instead of preserving uncertainty.

## reachable-only

### Raw output

`target_score` 校验通过。

它依赖 `user_score`，表达式 `add(user_score, 1)` 推断结果为 `INT`、`SCALAR`、实体域 `USER`，与声明一致。`unrelated_broken` 不在 `target_score` 的可达依赖路径上，因此不影响本次目标特征校验。

### Assertion results

- PASS — The response evaluates `target_score` and its dependency `user_score` only.
- PASS — The response explicitly excludes `unrelated_broken` from the reachable path and does not fail on its malformed syntax.
- FAIL — The response labels the target as simply “校验通过” rather than explicitly limiting success to rule validation only.

### Observed failure pattern

It scopes validation correctly but overstates the success label instead of identifying it as rule validation only.

## unknown-operator-incomplete

### Raw output

校验失败：`future_transform` 未注册，无法解析其语义、输入约束与输出类型。

因此不能生成 `future_output` 的完整衍生属性。可确定的基础配置为：

```json
{
  "name": "future_output",
  "definition_type": "DERIVED",
  "expression": "future_transform(base_a)"
}
```

请先注册 `future_transform` 并声明其类型、形状和实体域推断规则；之后才能校验并填充 `type`、`value_shape`、`entity_scopes` 与 `seq_max_length`。

### Assertion results

- FAIL — The response immediately labels the expression a validation failure and never records that its syntax initially passes.
- PASS — The response leaves semantics incomplete because `future_transform` has no available semantic contract.
- FAIL — The response asserts that `future_transform` is unregistered, rather than remaining neutral about whether the unknown operator is registered or illegal.
- PASS — The response does not invent output type, value shape, entity scopes, or maximum length.

### Observed failure pattern

It conflates an unknown operator’s missing contract with a registry failure, obscuring the successful syntax phase.
