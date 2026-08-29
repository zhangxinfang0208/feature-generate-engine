# Skill-enabled behavioral evaluation

Evaluator contexts used the shared Skill and only the routed references. The syntax regression used fresh high-reasoning `gpt-5.6-sol`; earlier `gpt-5.6-terra` contexts auto-repaired the malformed input, so this evidence does not claim deterministic behavior across host models.

## syntax-stop

### Raw output

```text
阶段 1｜语法校验：失败

- 表达式：`zip_concat(slice_by_indices(seq_a, find_indices(key_seq, target)), seq_b, {"delimiter":"#"}`
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

### Comparison with baseline

- Improved: preserves the submitted input with an immutable EOF marker, audits the original delimiter stream, and stops before BASE work.
- Regressed: None observed.

## extract-and-do-not-guess

### Raw output

The fresh `gpt-5.6-sol` output reported syntax success with unchanged normalized input and an empty delimiter stack, then listed exactly these six BASE references in first-seen order:

1. `auid_hwdsp_clk_crtv_clstid_seq_time_365d`
2. `auid_hwdsp_clk_norm_tag1id_seq_time_365d`
3. `normalized_tag1_id_h`
4. `auid_hwdsp_clk_slotid_seq_time_365d`
5. `auid_hwdsp_clk_ts_seq_time_365d`
6. `timestamp_s`

It explicitly excluded operator names, object keys, and string literals. In BASE completion it reported the supplied `auid_hwdsp_clk_ts_seq_time_365d.value_shape=SCALAR` as a conflict with its sequence use and stated that no overwrite property would be emitted. It requested unresolved types, shapes, scopes, lengths, and the missing derived name rather than inferring them.

The DERIVED result was `信息不完整`; it stated that multiple source lengths/shapes/index-validity facts were unknown and that the timestamp sequence had a shape conflict. It did not emit a derived `seq_max_length` property and placed that fact in the pending questions. It emitted only confirmed `definition_type=DERIVED`, `type=STRING`, `value_shape=SEQUENCE`, and escaped expression properties.

### Assertion results

- PASS — Lists exactly six BASE names in first-seen order.
- PASS — Does not treat operator names, object keys, or string literals as BASE features.
- PASS — Reports the existing timestamp `value_shape` conflict and emits no overwrite property.
- PASS — Asks for unknown type/length and does not guess a derived maximum from the single known `365`.

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

### Comparison with baseline

- Improved: makes the rule-validation-only boundary explicit while retaining target-rooted reachability.
- Regressed: None observed.

## unknown-operator-incomplete

### Raw output

```text
阶段 1：语法受理：通过；原表达式保持不变，分隔符栈为空，未自动修复输入。
阶段 2：BASE 发现：`base_a`。
阶段 3：BASE 补全：`base_a` 已完整，新增属性 `[]`。
阶段 4：DERIVED 补全：信息不完整。当前没有 `future_transform` 的可用语义契约，无法推断 `future_output` 的 type、value_shape、entity_scopes 或 seq_max_length；这不表示该算子未注册或非法。
可确定新增属性：`definition_type=DERIVED`、expression 字符串和 `to_use=true`；不生成未知语义字段。
阶段 5/6：规则校验回退结果为 `INCOMPLETE`，原因是缺少 `future_transform` 语义契约。
边界：仅规则校验，不代表引擎执行结果。
```

### Assertion results

- PASS — Initial syntax passes.
- PASS — Semantic result is incomplete because `future_transform` has no contract.
- PASS — Does not claim the operator is registered or illegal.
- PASS — Does not invent derived type, shape, scope, or maximum length.

### Comparison with baseline

- Improved: separates successful syntax from incomplete semantics and remains neutral about registration/legal status.
- Regressed: None observed.
