# 广告业务外部黄金用例

这组用例模拟广告排序中的“同业广告疲劳度”特征。候选广告携带行业和多个基础分数组件，用户侧携带近期广告行业、行为序列；Engine 生成同业触达、行为丰富度、疲劳分桶和疲劳校正分数。用例只通过公共 `FeatureDagEngine` API 执行，可直接用于外部系统的数据对比。

## 输入特征

| 特征 | 形状 | 示例 | 含义 |
| --- | --- | --- | --- |
| `candidate_ad_industry` | 标量 | `["GAME"]` | 候选广告所属行业 |
| `recent_ad_industry_seq` | 序列 | `["GAME", "FINANCE", "GAME"]` | 用户近期接触的广告行业 |
| `recent_ad_action_seq` | 序列 | `["expose", "click", "convert"]` | 与行业序列逐位置对齐的行为 |
| `candidate_score_component_seq` | 序列 | `[2.0, 4.5, 8.0]` | CTR、CVR、出价等基础分数组件 |

公共 API 要求所有输入均为 `Map<String, List<?>>`，所以标量也使用单元素数组包装。

## 模型表达式

| 输出特征 | 表达式 |
| --- | --- |
| `same_industry_positions` | `find_indices(recent_ad_industry_seq, candidate_ad_industry)` |
| `same_industry_ads` | `slice_by_indices(recent_ad_industry_seq, same_industry_positions)` |
| `same_industry_actions` | `slice_by_indices(recent_ad_action_seq, same_industry_positions)` |
| `same_industry_action_tokens` | `zip_concat(same_industry_ads, same_industry_actions, {"delimiter":"\|"})` |
| `distinct_same_industry_action_count` | `count_distinct(same_industry_action_tokens)` |
| `same_industry_event_count` | `get_seq_length(same_industry_positions)` |
| `ad_fatigue_bucket` | `discrete(same_industry_event_count, [0, 1, 3, 5])` |
| `ad_fatigue_log2` | `log_base(ad_fatigue_bucket, 2, 16)` |
| `fatigue_adjusted_score_components` | `calc_delta_seq(candidate_score_component_seq, ad_fatigue_log2, {"direction":"ELEMENT_MINUS_BASE"})` |

这里的疲劳校正公式是示例规则：每个基础分数组件减去 `ad_fatigue_log2`。实际业务可以在不改变外部验证方式的前提下替换表达式和黄金结果。

## 文件与运行

- 模型特征集：`src/test/resources/external-golden-cases/advertising-model-feature-set.json`
- 输入及期望输出：`src/test/resources/external-golden-cases/advertising-offline-cases.json`
- 公共 API 校验器：`src/test/java/com/example/featuredag/blackbox/ExternalGoldenCasesVerifier.java`

PowerShell：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-advertising-golden-cases.ps1
```

Bash：

```bash
./scripts/run-advertising-golden-cases.sh
```

五条用例分别覆盖新用户、无同业广告、一次同业点击、三次同业触达且行为重复，以及五次同业触达的高疲劳场景。校验时字符串和整数精确比较、数组顺序敏感，浮点数使用 `1e-9` 绝对误差。
