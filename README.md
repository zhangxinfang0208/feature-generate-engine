# Feature DAG Engine

这是一个基于 Java 21 的三层特征表达式 DAG 引擎参考实现。它通过公共 `init`/`generate` API 同时支持 Spark/Scala 离线调用和在线 Java 调用。

1. **特征定义与表达式层**：`FeatureDefinition` 与临时 AST。
2. **逻辑 DAG 层**：`LogicalDag`、`SourceNode`、`LiteralNode`、`OperatorNode`、`FeatureOutputNode`。
3. **物理执行与 Runtime 层**：`PhysicalPlan`、`PhysicalNode`、`ExecutionContext`、`DagRuntime`。

优化信息独立保存在 `PlannerMetadata`，每次执行的状态独立保存在 `RuntimeNodeState`，避免把所有属性堆到 DAG 节点上。

## 示例能力

- 表达式解析：函数调用、特征引用、数值、字符串、对象参数。
- 目标驱动构图：Transform 使用共享特征集全部目标；在线只构建模型入模特征依赖闭包。
- 循环依赖检测和类型推导。
- 实体范围推导：`USER`、`SCENE`、`ITEM`。
- 在线阶段划分：`REQUEST_SHARED`、`CANDIDATE_BATCH`。
- 在线算子融合：`count(extractIndustry(...))` 融合成 `COUNT_INDUSTRY_BATCH`。
- 候选参数去重：按 `item_industry` 去重，而不是按 `itemId` 重复执行。
- 序列索引：`industry -> positions`。
- 零拷贝序列：`SequenceBlock + SequenceView`。
- 离线完整特征输出与在线子图执行。

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

## 直接运行

环境要求：JDK 21 或更高版本。

```bash
./scripts/run-demo.sh
```

运行无外部依赖的自测试：

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

## JSON 配置与公共 API

配置沿用业务侧 `features`，并增加 `derivedFeatures`：

```json
{
  "features": [
    {
      "name": "price",
      "raw_name": "raw_price",
      "store_name": "price",
      "type": "DOUBLE",
      "dft": 0.0,
      "to_use": true,
      "entity_scopes": ["ITEM"]
    },
    {
      "name": "quality_score",
      "raw_name": "quality_score",
      "type": "DOUBLE",
      "dft": 0.0,
      "entity_scopes": ["ITEM"]
    }
  ],
  "derivedFeatures": [
    {
      "name": "normalized_price",
      "type": "DOUBLE",
      "expression": "normalize(price, {\"min\":0,\"max\":1000})",
      "output_policy": "INTERNAL_ONLY"
    },
    {
      "name": "price_score",
      "store_name": "price_score_out",
      "type": "DOUBLE",
      "expression": "multiply(normalized_price, quality_score)",
      "output_policy": "OUTPUT"
    }
  ],
  "feature_set_name": "test_001",
  "version": "latest"
}
```

- `name` 是表达式引用的逻辑名。
- `raw_name` 是 `generate` 输入 Map 中的字段名。
- `store_name` 是最终结果 Map 中的字段名。
- `INTERNAL_ONLY` 特征会进入依赖闭包，但不会出现在结果中。
- 在线依赖到的原始特征必须通过 JSON 或 `InitOptions.rawFeatureScopes` 声明 `USER`、`SCENE` 或 `ITEM`。

离线 Java 调用：

```java
FeatureDagEngine engine = FeatureDagEngine.init(
        configJson, InitOptions.offline("offline-plan-v1"));
GenerateResult result = engine.generate(
        new OfflineGenerateRequest("row-1", rowValues));
Map<String, Object> features = result.featureValues();
```

在线 Java 在进程启动时初始化一次，之后并发复用同一个 engine：

```java
FeatureDagEngine engine = FeatureDagEngine.init(
        configPath, InitOptions.online("online-plan-v1"));
GenerateResult result = engine.generate(
        new OnlineGenerateRequest(requestId, sharedValues, candidates));
```

Spark/Scala 不需要引擎依赖 Spark API，在每个 partition 初始化一次：

```scala
val configBroadcast = spark.sparkContext.broadcast(configJson)

dataset.mapPartitions { rows =>
  val engine = FeatureDagEngine.init(
    configBroadcast.value,
    InitOptions.offline("offline-plan-v1")
  )
  rows.map { row =>
    engine.generate(
      new OfflineGenerateRequest(row.getAs[String]("id"), rowValues(row))
    ).featureValues()
  }
}
```

## 完整案例

表达式：

```text
same_industry_count = count(extractIndustry(user_seq1, item_industry))
final_score = multiply(user_click_score, item_price_log)
```

在线请求包含：

```text
item1 -> industry1
item2 -> industry2
item3 -> industry1
```

在线物理计划将 `extractIndustry + count` 融合为批量节点，先把行业参数从 3 个候选去重为 2 个唯一行业，再通过序列行业索引计算，最后映射回 3 个候选。

Transform 因为还要输出 `same_industry_seq`，不会删除序列节点，而是让 `extractIndustry` 返回共享底层 `SequenceBlock` 的 `SequenceView`，`count` 直接消费视图。

## 设计边界

- 该实现不直接依赖 Spark Dataset API，也不包含 RPC 服务、模型热更新或分布式缓存。
- 离线跨行复用在数据已按用户聚簇时可扩展为 `USER_GROUP` 执行；当前 Demo 以单行执行展示语义。
- `SequenceView` 解决执行期中间数组复制；最终必须落盘的序列特征仍需在输出边界物化或使用专用引用格式。
