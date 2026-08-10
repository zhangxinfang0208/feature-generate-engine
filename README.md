# Feature DAG Engine

这是一个基于 Java 21 的三层特征表达式 DAG 引擎参考实现。它通过公共 `init`/`generate` API 同时支持 Spark/Scala 离线调用和在线 Java 调用。

1. **特征定义与表达式层**：`FeatureDefinition` 与临时 AST。
2. **逻辑 DAG 层**：`LogicalDag`、`SourceNode`、`LiteralNode`、`OperatorNode`、`FeatureOutputNode`。
3. **物理执行与 Runtime 层**：`PhysicalPlan`、`PhysicalNode`、`ExecutionContext`、`DagRuntime`。

优化信息独立保存在 `PlannerMetadata`，每次执行的状态独立保存在 `RuntimeNodeState`，避免把所有属性堆到 DAG 节点上。

## 示例能力

- 表达式解析：函数调用、特征引用、数值/字符串/布尔/null 值、对象参数、数组字面量、柯里化调用和数字算子名。
  例如：`discrete(a, [1, 10, 100])`、`slice_v3_typed({"start": 4})(seq)`。算子完成注册和类型推导不代表每个序列算子都具备 Runtime 求值支持。
- 目标驱动构图：Transform 使用共享特征集全部目标；在线只构建模型入模特征依赖闭包。
- 循环依赖检测和类型推导。
- 实体范围推导：`USER`、`SCENE`、`ITEM`。
- 在线阶段划分：`REQUEST_SHARED`、`CANDIDATE_BATCH`。
- 在线算子融合：根据算子语义把“按 key 过滤序列后计数”改写为注册式 `sequence-key-count` 执行器。
- 候选参数去重：按 `item_industry` 去重，而不是按 `itemId` 重复执行。
- 序列索引：`industry -> positions`。
- 零拷贝序列：`SequenceBlock + SequenceView`。
- 普通元素数组序列：`auid_app_time_seq`（`STRING + SEQUENCE`）与 `timestamp`（`INT + SEQUENCE`）可用于窗口特征。
- 三天 app 点击计数：`auid_omnichannel_paid_cnt_3d` 可直接从两条原始序列生成。
- 离线完整特征输出与在线子图执行。

## 算子支持情况

`OperatorRegistry.standard()` 当前注册 38 个算子。注册成功表示表达式可以完成解析、参数数量校验、类型/shape 推导和逻辑 DAG 构建；只有下表标记为“可执行”的算子支持 Runtime 计算。

### 已支持 Runtime 计算

| 算子 | 签名 | 计算能力 |
|---|---|---|
| `coalesce` | `coalesce(a, b, ...)` | 返回第一个非 `null` 的值 |
| `normalize` | `normalize(a, {"min": m, "max": n})` | min-max 归一化 |
| `extractIndustry` | `extractIndustry(seq, industry)` | 按行业过滤事件序列 |
| `count` | `count(seq)` | 计算序列、集合或数组长度 |
| `greater_in_sequence_typed` | `greater_in_sequence_typed(seq, base, {"margin": m})` | 返回大于 `base - margin` 的元素索引 |
| `list_index_typed` | `list_index_typed(seq, indices)` | 按索引抽取列表元素 |
| `find_list_index_typed` | `find_list_index_typed(seq, target)` | 返回所有等于目标值的位置 |
| `add` | `add(a, b, ...)` | 多个数值相加 |
| `log` | `log(a)` | 自然对数 |
| `multiply` | `multiply(a, b)` | 两个数值相乘 |
| `sub` | `sub(a, b)` | 两个数值相减 |
| `sign` | `sign(a)` | 返回 `-1`、`0` 或 `1` |
| `div_num` | `div_num(a, {"divisor": d})` | 数值除法 |
| `round` | `round(a)` | 四舍五入为整数 |
| `discrete` | `discrete(a, boundaries)` | 按严格递增边界分桶；命中边界进入右侧桶 |
| `log_base` | `log_base(a, base, upbound)` | 指定底数并带上限的对数计算 |
| `slice_by_indices` | `slice_by_indices(seq, indices)` | 按下标数组抽取序列元素 |
| `find_indices` | `find_indices(seq, target)` | 返回所有等于目标值的下标 |
| `get_seq_length` | `get_seq_length(seq)` | 返回序列、集合或数组长度 |
| `count_distinct` | `count_distinct(seq)` | 计算序列中的不同值个数 |
| `zip_concat` | `zip_concat(seq1, seq2, ..., {"delimiter":"#"})` | 等长序列逐位置拼接，分隔符默认 `#` |
| `calc_delta_seq` | `calc_delta_seq(seq, base)` | 对普通数值列表计算 `element - base` |

### 已注册但暂不支持 Runtime 计算

以下算子已经支持表达式解析、参数校验、类型/shape 推导和 DAG 构建，但调用 `evaluate()` 时会抛出带算子名的 `UnsupportedOperationException`：

| 分类 | 算子 |
|---|---|
| 序列索引与选择 | `greater_than_index_typed`、`reverse_typed`、`slice_v3_typed`、`intersection_typed`、`uniq_key_index` |
| 序列与映射转换 | `64`、`list_2_map`、`thf_default_`、`value2key`、`k2v`、`k2v_f`、`v2v`、`multi_v2` |
| 序列或离散计算 | `list_multi`、`dis2xl`、`default_key_if` |

业务接入时应根据上表确认目标算子是否具备执行实现。

## 目录

```text
src/main/java/com/example/featuredag
├── definition   # L0 特征定义数据模型
├── expression   # 临时 AST 和表达式解析器
├── logical      # 逻辑 DAG 数据模型和构图器
├── planning     # 独立规划元数据和优化分析
├── physical     # 离线/在线物理计划
├── runtime      # 执行上下文、ValueHandle、SequenceView、Runtime
├── operator     # 算子定义、推导和执行注册表
└── demo         # 完整构图与执行案例
```

算子语义、物理改写、专用执行器、序列索引与缓存的扩展约束见
[`docs/architecture/operator-optimization-extension.md`](docs/architecture/operator-optimization-extension.md)。

## 直接运行

环境要求：JDK 21 或更高版本。

```bash
./scripts/run-demo.sh
```

`DagDemo`、`OfflineBatchDemo` 和 `OnlineGroupedBatchDemo` 都加载同一份
[`src/main/resources/demo/config.json`](src/main/resources/demo/config.json)，并通过
`InitOptions.targetFeatures` 选择各自需要的目标子图。上面的命令仍以原有 `DagDemo` 作为
JAR 入口；它保留三天 app 点击计数，并用同一份输入分别执行离线与在线计划、校验结果一致。
两个 Batch Demo 使用独立入口，不会替换 JAR manifest：

```bash
mvn -q -DskipTests package

java -cp target/feature-dag-engine-1.0.0-SNAPSHOT-all.jar \
  com.example.featuredag.demo.OfflineBatchDemo

java -cp target/feature-dag-engine-1.0.0-SNAPSHOT-all.jar \
  com.example.featuredag.demo.OnlineGroupedBatchDemo
```

- `OfflineBatchDemo`：把两个 user 的三个 candidate 展平为三行 RAW 输入，展示离线逐行 Batch；
- `OnlineGroupedBatchDemo`：一次提交 `user-a`、`user-b`、`user-empty` 三个分组，展示
  USER 共享值广播、不同 candidate 数量、请求级输出和候选级输出。自测试会把在线共享输出与
  candidate 输出合并后逐行对照离线结果，验证统一配置在两种执行环境下的语义一致。

运行断言自测试（需要启用 Java assertions）：

```bash
./scripts/run-self-test.sh
```

## Maven

项目提供 `pom.xml`。安装 Maven 后可执行：

```bash
mvn package
java -jar target/feature-dag-engine-1.0.0-SNAPSHOT-all.jar
```

构建会生成普通 thin JAR 和包含、重定位 Jackson 的自包含 `-all.jar`。Spark executor 与在线 JVM 都必须运行 Java 21。

## Demo 输入约定

`com.example.featuredag.demo.DagDemo` 使用下面这条真实调用链路：

```text
auid = ["aaaa"]
auid_app_time_seq = [app0, app1, app2, ...]
timestamp = [1785549653, 1785459831, 1785286488, ...]
request_time = [1785549653]
target_app = ["app0"]
```

`auid_app_time_seq` 和 `timestamp` 是从近到远排列的普通 Java `List`。时间戳示例使用秒；
三天窗口参数因此是 `259200`。第一版不会验证输入形状、元素类型或跨序列 alignment，也不要求
所有原始序列具有相同长度。

## JSON 配置与公共 API

配置使用单一的 `features` 数组；BASE 和 DERIVED 声明按依赖顺序共同放入其中。旧顶层
`derivedFeatures` 格式会在加载时被拒绝。

```json
{
  "features": [
    {
      "name": "price",
      "raw_name": "raw_price",
      "store_name": "price",
      "type": "DOUBLE",
      "definition_type": "BASE",
      "dft": 0.0,
      "to_use": true,
      "entity_scopes": ["ITEM"],
      "value_shape": "SCALAR"
    },
    {
      "name": "quality_score",
      "raw_name": "quality_score",
      "type": "DOUBLE",
      "dft": 0.0,
      "entity_scopes": ["ITEM"],
      "value_shape": "SCALAR"
    },
    {
      "name": "normalized_price",
      "type": "DOUBLE",
      "definition_type": "DERIVED",
      "expression": "normalize(price, {\"min\":0,\"max\":1000})",
      "output_policy": "INTERNAL_ONLY",
      "entity_scopes": ["ITEM"],
      "value_shape": "SCALAR"
    },
    {
      "name": "price_score",
      "store_name": "price_score_out",
      "type": "DOUBLE",
      "definition_type": "DERIVED",
      "expression": "multiply(normalized_price, quality_score)",
      "output_policy": "OUTPUT",
      "entity_scopes": ["ITEM"],
      "value_shape": "SCALAR"
    }
  ],
  "feature_set_name": "test_001",
  "version": "latest"
}
```

- `name` 是表达式引用的逻辑名。
- `raw_name` 是 `generate` 输入 Map 中的字段名。
- `store_name` 是最终结果 Map 中的字段名。
- `definition_type` 明确枚举为 `BASE` 或 `DERIVED`；BASE 省略、`null` 或空白时，为兼容历史配置仍按 `BASE` 处理。DERIVED 必须提供 `expression`，BASE 的 `expression` 必须为空。
- `entity_scopes` 可声明 `USER`、`SCENE`、`ITEM`。BASE 的范围用于源特征；DERIVED 的非空声明会与表达式推导结果校验一致。
- `value_shape` 可声明 `SCALAR`、`SEQUENCE`、`VECTOR`。`VECTOR` 是配置边界的名称，内部映射为候选维度向量；DERIVED 的声明同样会与推导形状校验一致。
- 所有公共输入值都是普通 Java `List`。`SCALAR` 在外部表示为 `[value]`，并由配置的
  `value_shape` 在内部解包；`SEQUENCE` 保持为 `[v1, v2, ...]`。`EVENT_SEQUENCE` 仅用于低层
  `SequenceBlock`/`SequenceView` 场景，普通序列使用元素 data type（如 `STRING` 或 `INT`）加
  `SEQUENCE`。
- 候选 ITEM 标量是每个 candidate Map 内的单元素 `List`；在线 ITEM 候选轴仍使用
  `CandidateVectorValue`，不会与用户序列混淆。
- 调用方必须在调用引擎前把旧的 `ratings=["1|0|1|v2"]` 协议转换为干净数组
  `ratings=[1,0,1]`。
- v1 不验证输入形状、元素类型或跨序列 alignment，也不要求所有原始序列具有相同长度。
- `expression` 是 DERIVED 特征的表达式文本；`output_policy` 使用 `OUTPUT` 或 `INTERNAL_ONLY` 控制最终输出边界。DERIVED 的 `output_policy` 缺失或为空白时，默认是 `OUTPUT`。
- `INTERNAL_ONLY` 特征会进入依赖闭包，但不会出现在结果中。
- 在线依赖到的原始特征必须通过 JSON 或 `InitOptions.rawFeatureScopes` 声明 `USER`、`SCENE` 或 `ITEM`。

离线 Java 调用：

```java
Map<String, List<?>> rowValues = Map.of(
        "raw_price", List.of(100.0),
        "quality_score", List.of(0.8));
FeatureDagEngine engine = FeatureDagEngine.init(
        configJson, InitOptions.offline("offline-plan-v1"));
Map<String, List<?>> features = engine.generate(
        new OfflineGenerateRequest("row-1", rowValues)).featureValues();
```

离线吞吐场景应一次提交多行；引擎只遍历一次物理计划，结果行与输入行按下标一一对应：

```java
List<Map<String, List<?>>> rows = List.of(
        Map.of("raw_price", List.of(100.0), "quality_score", List.of(0.8)),
        Map.of("raw_price", List.of(500.0), "quality_score", List.of(0.5)));
OfflineBatchGenerateResult result = engine.generateBatch(
        new OfflineBatchGenerateRequest("partition-1-batch-1", rows));
List<Map<String, List<?>>> outputRows = result.rows();
```

在线 Java 在进程启动时初始化一次，之后并发复用同一个 engine：

```java
Map<String, List<?>> sharedValues = Map.of("request_time", List.of(1785549653));
List<Map<String, List<?>>> candidates = List.of(
        Map.of("raw_price", List.of(100.0), "quality_score", List.of(0.8)));
FeatureDagEngine engine = FeatureDagEngine.init(
        configPath, InitOptions.online("online-plan-v1"));
GenerateResult result = engine.generate(
        new OnlineGenerateRequest(requestId, sharedValues, candidates));
```

一次需要处理多个 user/request 时，使用分组 Batch，避免把不同 user 的 candidate 混入同一个共享上下文：

```java
List<OnlineRequestGroup> groups = List.of(
        new OnlineRequestGroup("user-a", userAValues, userACandidates),
        new OnlineRequestGroup("user-b", userBValues, userBCandidates));

OnlineBatchGenerateResult batchResult = engine.generateBatch(
        new OnlineBatchGenerateRequest("online-batch-1", groups));

GenerateResult userAResult = batchResult.groupResults().get(0);
```

在线 Batch 会把 candidate 展平执行，但通过 group offsets 保持 USER/SCENE 广播、缓存和输出边界；详细设计见
[在线分组 Batch 执行设计](docs/architecture/online-grouped-batch-execution.md)。

输出也始终使用 `List`，包括标量输出。DAG 阶段在 `preTransform`、hash 或模型编码之前运行。
`featureValues()` 合并到 shared/user features；`candidateFeatureValues().get(i)` 按原有顺序合并到
candidate `i`。合并时的名称冲突和 `ExecutionSession` 写入属于外部
`FeatureDagGenerateTask`，不在本仓库处理。v1 采用 fail-fast：失败时不会返回部分输出或 Runtime
fallback。

Spark/Scala 不需要引擎依赖 Spark API，在每个 partition 初始化一次并分批执行：

```scala
import scala.jdk.CollectionConverters.*

val configBroadcast = spark.sparkContext.broadcast(configJson)

dataset.mapPartitions { rows =>
  val engine = FeatureDagEngine.init(
    configBroadcast.value,
    InitOptions.offline("offline-plan-v1")
  )
  rows.grouped(1024).flatMap { batch =>
    val rawBatch = batch.map(rowValues).asJava
    engine.generateBatch(
      new OfflineBatchGenerateRequest("partition-batch", rawBatch)
    ).rows().asScala
  }
}
```

## 完整案例：三天内用户点击 app 次数

Demo 的 DERIVED 特征表达式是：

```text
count(
  find_list_index_typed(
    list_index_typed(
      auid_app_time_seq,
      greater_in_sequence_typed(timestamp, request_time, {"margin":259200})
    ),
    target_app
  )
)
```

对应的离线 Java 调用是：

```java
FeatureDagEngine engine = FeatureDagEngine.init(
        configJson, InitOptions.offline("three-day-app-count-demo"));
GenerateResult result = engine.generate(new OfflineGenerateRequest(
        "auid-aaaa-row",
        Map.of(
                "auid", List.of("aaaa"),
                "auid_app_time_seq", List.of("app0", "app1", "app2", "app3", "app4"),
                "timestamp", List.of(1785549653L, 1785459831L, 1785286488L,
                        1785203315L, 1785114236L),
                "request_time", List.of(1785549653),
                "target_app", List.of("app0"))));
```

计算过程为：

1. `greater_in_sequence_typed` 找到 `timestamp > request_time - 259200` 的下标。
2. `list_index_typed` 用这些下标抽取同位置的 app，保持两条序列的事件对齐。
3. `find_list_index_typed` 找到目标 app 的所有位置。
4. `count` 输出目标 app 的点击次数。

对于上面的示例，三天前的时间戳是 `1785290453`，有效下标为 `0、1`，窗口内 app 为
`[app0, app1]`，因此 `target_app=app0` 时输出：

```text
FEATURES: {auid_omnichannel_paid_cnt_3d=[1]}
```

`timestamp` 恰好等于窗口边界的事件不会被计入，因为窗口判断使用严格大于 `>`。
按行业 key 的融合计数仍严格遵守输入 `SequenceView` 的可见范围；索引和候选计数缓存仅属于当前
`generate` 请求，不会跨请求共享。

## 设计边界

- 该实现不直接依赖 Spark Dataset API，也不包含 RPC 服务、模型热更新或分布式缓存。
- 离线批执行在一次运行时遍历中传播整批值；跨行的用户级状态复用仍可在数据按用户聚簇时扩展为 `USER_GROUP` 执行。
- `SequenceView` 解决执行期中间数组复制；最终必须落盘的序列特征仍需在输出边界物化或使用专用引用格式。
