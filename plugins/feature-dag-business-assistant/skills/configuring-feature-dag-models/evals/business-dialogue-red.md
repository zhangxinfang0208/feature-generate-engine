# Business dialogue RED evidence

## Scenario

The business supplies only `add(user_score, 1)`. No BASE configuration has been supplied yet.

## Observed failure A

The old protocol returns a deeply nested JSON envelope containing six stages, empty arrays, internal validator state, and a machine-oriented `next_request`. The business must inspect protocol internals to discover that `user_score` configuration is needed.

## Observed failure B

A fresh host skipped the existing-configuration intake and immediately asked for fields:

```text
特征配置助手 · 需要补充 BASE 特征信息

表达式引用了 1 个尚未配置的 BASE（RAW）特征：user_score。

RAW 特征 user_score 的定义（必需）
- 值类型：INT / BIGINT / DOUBLE
- 值形状：SCALAR 还是 SEQUENCE
- 实体域 entityScopes：如 user / request / candidate
```

Another fresh host went further and guessed `type=DOUBLE`, `entity_scopes=["USER"]`, `raw_name=user_score`, and a DERIVED feature name, then generated a complete model.

## Required correction

After syntax succeeds and BASE references are extracted, the first and only request is to copy those BASE features' current frontend configuration. Do not ask for missing facts, infer defaults, or generate additions until that current configuration has been received and checked feature by feature.
