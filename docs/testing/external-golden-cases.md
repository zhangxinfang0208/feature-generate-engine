# 外部调用黄金用例

这组用例从推荐业务背景出发，只经过 `FeatureDagEngine` 公共 API 验证结果，不依赖逻辑 DAG、物理计划或算子实现细节。调用方可以把同一份模型特征集加载到自己的进程中，逐条传入输入数据，再将返回值与黄金输出比较。

## 文件

- 模型特征集：`src/test/resources/external-golden-cases/recommendation-model-feature-set.json`
- 输入和期望输出：`src/test/resources/external-golden-cases/recommendation-offline-cases.json`
- 可执行校验器：`src/test/java/com/example/featuredag/blackbox/ExternalGoldenCasesVerifier.java`

用例文件中的所有输入值都遵循公共契约 `Map<String, List<?>>`：标量也必须放进单元素数组，例如 `"current_category": ["sports"]`；序列直接使用完整数组。输出采用相同规则，标量输出也是单元素数组。

## 模型表达式

| 输出特征 | 表达式 | 业务含义 |
| --- | --- | --- |
| `matched_positions` | `find_indices(history_category_seq, current_category)` | 找到当前类目的全部历史位置 |
| `matched_categories` | `slice_by_indices(history_category_seq, matched_positions)` | 截取匹配类目 |
| `matched_actions` | `slice_by_indices(history_action_seq, matched_positions)` | 截取相同位置的行为 |
| `matched_category_actions` | `zip_concat(matched_categories, matched_actions, {"delimiter":"\|"})` | 组合类目与行为上下文 |
| `distinct_matched_context_count` | `count_distinct(matched_category_actions)` | 统计不同的匹配上下文数 |
| `matched_event_count` | `get_seq_length(matched_positions)` | 统计历史匹配次数 |
| `matched_event_bucket` | `discrete(matched_event_count, [0, 1, 3, 5])` | 对匹配次数分桶 |
| `matched_event_log2` | `log_base(matched_event_bucket, 2, 16)` | 对匹配强度做对数变换 |
| `normalized_score_seq` | `calc_delta_seq(history_score_seq, matched_event_log2)` | 每个历史分数减去匹配强度 |

这条链路覆盖首期全部 8 个算子。五个场景覆盖冷启动空历史、非空历史无匹配、单次匹配、重复上下文以及 3/5 两个精确分桶边界。每条用例中的历史类目、行为和分数序列都逐位置对齐。

## 外部 Java 调用方式

Engine 当前是 Java 库而不是 HTTP 服务。外部 JVM 应先读取模型 JSON 初始化一次 Engine，然后对每条数据调用公共 API：

```java
String configJson = Files.readString(featureSetPath, StandardCharsets.UTF_8);
FeatureDagEngine engine = FeatureDagEngine.init(
        configJson,
        InitOptions.builder()
                .environment(ExecutionEnvironment.OFFLINE)
                .targetFeatures(new LinkedHashSet<>(targetFeaturesFromCaseFile))
                .build());

GenerateResult actual = engine.generate(new OfflineGenerateRequest(
        executionIdFromCase,
        rowValuesFromCase));

Map<String, List<?>> actualValues = actual.featureValues();
// 与 expected_output.feature_values 逐字段、逐位置比较。
```

一次执行全部四行时，可把各用例的 `input.row_values` 按文件顺序组成 `rows`：

```java
OfflineBatchGenerateResult actual = engine.generateBatch(
        new OfflineBatchGenerateRequest("external-golden-batch", rows));
```

`actual.rows()` 与输入行严格按下标对应。字符串和整数精确比较，数组顺序敏感；浮点数使用用例文件声明的绝对误差 `1e-9`。

## 本地验证

PowerShell：

```powershell
./scripts/run-external-golden-cases.ps1
```

Bash：

```bash
./scripts/run-external-golden-cases.sh
```

校验器会用同一套黄金数据分别执行 5 次单条请求和 1 次离线批请求，并在任何字段不一致时报告完整 JSON 路径。
