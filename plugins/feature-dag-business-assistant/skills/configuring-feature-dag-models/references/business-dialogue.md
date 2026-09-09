# Business Dialogue

Use one short Chinese message for the active business step. Keep the labels and section order below. Replace placeholders with actual names and values; do not expose internal stages, protocol fields, skipped work, or empty diagnostic collections. Render the Chinese prompt as ordinary Markdown. Fence only copyable JSON input examples and frontend additions; do not wrap the whole response in a `text` or other code fence.

## 1. Request an expression

```text
请提供需要校验的特征表达式。

回复样例：

表达式：zip_concat(...)
```

## 2. Report a syntax error

```text
表达式语法校验失败。

问题：<简短错误>
位置：<字符偏移或输入末尾>

请修正后重新提交完整表达式。

回复样例：

表达式：zip_concat(...)
```

Do not show BASE work after a syntax error. Never append a missing token to the displayed expression.

## 3. Request current BASE configuration

This is the mandatory first request after syntax succeeds. Use it when any referenced BASE current entry has not been supplied. List only entries still needed, in AST first-seen order.

```text
表达式语法校验通过。

衍生特征：target_score

发现以下 BASE 特征：
- user_score
- item_score

请从前台复制以上 BASE 特征的当前配置。
请保留前台已有字段，不要先手工补充或改写。

回复样例：

{
  "features": [
    {
      "name": "user_score",
      "raw_name": "user_score",
      "catalog": "/business/current/path"
    }
  ]
}
```

Show the `衍生特征` line only when the name is already known, including from `derived_name=expression` shorthand. The example shows the wrapper shape only. Tell the business to retain every field copied from the frontend. The business may paste a full model configuration containing unrelated features. When that full configuration answers this current-BASE request, it is BASE metadata input—not a final model submission—even if it contains `feature_set_name` and `version`. Select required BASE entries by exact `name`, ignore unrelated entries, keep AST first-seen order, and request only referenced names that are absent. Report duplicate entries for a referenced `name` as a conflict; never match by `raw_name`. At this step do not ask for `type`, `value_shape`, `entity`, `entity_scopes`, or `seq_max_length` as manually entered facts. Do not infer them, emit additions, create a DERIVED name, generate a complete model, or return a final verdict.

## 4. Request missing BASE facts and conflict corrections

Enter this step only after the current frontend entry for every referenced BASE has been received. Check every feature, then batch all actionable work into one response. Omit a subsection when it has no items.

```text
BASE 特征信息校验完成，以下信息需要业务补充：

特征 user_seq：
- type
- value_shape
- entity_scopes

特征 item_id：
- entity_scopes

请按下面的样例一次性回复：

特征 user_seq：
type: STRING
value_shape: SEQUENCE
entity: USER

特征 item_id：
entity: ITEM
```

The reply example contains only fields actually missing for each feature. Use `entity` as the easy input label and map it to `entity_scopes`; multiple scopes are comma-separated, for example `entity: USER, ITEM`. Use representative values (`STRING`, `SEQUENCE`, `USER`, `365`) as editable examples, never as inferred answers.

Absent `definition_type` and `to_use` never appear in this business-facts request. They produce deterministic frontend additions `definition_type=BASE` and `to_use=true` after other BASE facts are known. Existing contradictory values remain conflicts.

When populated values conflict, append this subsection after the missing-facts subsection:

```text
以下已有配置存在冲突：

特征 event_seq：
- value_shape：当前值 SCALAR；需要值 SEQUENCE

冲突字段请在前台自行修改，并重新复制对应特征的当前配置。
你可以在同一条回复中同时提供上面的缺失信息。
```

Never emit an overwrite addition for a conflict.

## 5. Request a DERIVED name

If BASE facts are complete but the target DERIVED name is unknown:

```text
BASE 特征信息已完整。

请提供当前表达式对应的衍生特征名称。

回复样例：

衍生特征名：target_score
```

## 6. Return copyable additions

After BASE facts and the DERIVED name are known, return all determinable BASE and DERIVED additions in one response, grouped by feature. Each code block is a JSON array of exact five-key frontend properties in canonical field order.

```text
配置补充项已生成，请按特征复制到前台。

BASE 特征 user_seq：

<五字段属性 JSON 数组>

DERIVED 特征 target_score：

<五字段属性 JSON 数组>

全部属性填写完成后，请提供目标特征名和完整模型进行最终校验。

回复样例：

目标特征：target_score

{
  "feature_set_name": "model_name",
  "version": "1",
  "features": []
}
```

If operator semantics leave a DERIVED fact unresolved, return any deterministic additions first, then request the unresolved facts using the same per-feature text shape as BASE completion. In particular, an unknown operator does not block absent `definition_type=DERIVED`, the submitted `expression`, `output_policy=OUTPUT`, or `to_use=true` additions. Never invent an operator contract, type, shape, scope, or length.

For a missing operator contract, use this combined shape and omit any addition already present in the current configuration:

```text
配置补充项已生成，请按特征复制到前台。

DERIVED 特征 future_output：

<仅包含可确定字段的五字段属性 JSON 数组>

算子 future_transform 缺少可用语义契约，以下衍生特征信息需要业务补充：

特征 future_output：
- type
- value_shape
- entity_scopes
- seq_max_length

请按下面的样例一次性回复：

特征 future_output：
type: STRING
value_shape: SCALAR
entity: USER
seq_max_length: 1
```

## 7. Return final validation

```text
最终校验：PASS

目标特征：target_score
校验范围：target_score 及其可达依赖
校验边界：规则校验结果（未调用远程校验器）
问题：无
```

For `FAIL` or `INCOMPLETE`, replace the verdict and list issues by feature. If deterministic additions remain, include their feature-grouped JSON arrays before asking the business to correct and resubmit the same target and model wrapper. Ignore unrelated model entries.

## Cost controls

- Request all missing BASE entries in one turn.
- Let the business paste a full model; filter required BASE entries internally.
- After entries arrive, request all unresolved BASE facts in one turn, grouped by feature.
- Allow conflict corrections and missing facts in the same business reply.
- Return all BASE and DERIVED additions together when their values are known.
- Show only the active step; never narrate completed or skipped internal stages.
