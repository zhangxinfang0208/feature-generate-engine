# Feature DAG Engine 公共 init/generate API 设计

## 1. 目标

为现有 Java 21 特征表达式 DAG 引擎增加稳定的公共门面，使同一份特征配置和同一套执行核心可以同时用于：

- 离线 Spark：Scala 在 `mapPartitions` 中初始化一次引擎，并逐行调用生成接口。
- 在线 Java：进程启动时初始化一次引擎，并由多个请求线程并发调用生成接口。

公共能力只包含两个核心动作：`init` 构建不可变执行计划，`generate` 根据输入产生目标衍生特征。公共 API 不暴露 AST、逻辑 DAG、物理计划、`ExecutionContext` 或 `ValueHandle`。

## 2. 非目标

- 不直接提供 `Dataset<Row> -> Dataset<Row>` Spark API。
- 不依赖 Spark 或 Scala 的任何版本。
- 不实现 RPC 服务、配置中心、计划热更新或跨请求缓存。
- 不在本次改造中提供衍生特征计算失败后的默认值兜底。
- 不扩展当前算子集合或表达式语法。

## 3. 生命周期与线程模型

公共门面为初始化后不可变的 `FeatureDagEngine`：

```java
FeatureDagEngine engine = FeatureDagEngine.init(configPath, initOptions);
GenerateResult result = engine.generate(generateRequest);
```

提供两个同名初始化重载：

```java
public static FeatureDagEngine init(Path configFile, InitOptions options);

public static FeatureDagEngine init(String configJson, InitOptions options);
```

文件重载适用于在线 Java 或 executor 具有本地配置文件的部署。字符串重载适用于 Spark driver 读取 JSON 后广播配置文本；executor 在每个 partition 中初始化一次。引擎实例不要求可序列化，也不应捕获到由 driver 序列化的闭包中。

执行接口只有一个：

```java
public GenerateResult generate(GenerateRequest request);
```

引擎内部的算子注册表和物理计划只读。每次 `generate` 创建独立 `ExecutionContext`，因此同一个在线引擎实例可被多个线程安全调用，单次失败不会污染后续请求。

## 4. JSON 配置协议

顶层继续使用现有的 `features`、`derivedFeatures`、`feature_set_name` 和 `version`。下面是包含中间衍生特征的完整示例：

```json
{
  "features": [
    {
      "catalog": "/mock/dir",
      "encode": "separate",
      "regexp": "",
      "order": 3,
      "is_train_feature": true,
      "featureCategory": "offline",
      "name": "price",
      "table_name": "pps_dspperfm.mock_table",
      "to_use": true,
      "store_name": "price",
      "raw_name": "raw_price",
      "dcs_cluster_name": "",
      "feature_type": "sparse",
      "dft": 0.0,
      "feature_group_name": "creative_features",
      "type": "DOUBLE",
      "feature_source": "",
      "prim_keys": "creative_id",
      "discrete_type": "",
      "is_feedback": "true",
      "entity_scopes": ["ITEM"]
    },
    {
      "name": "quality_score",
      "raw_name": "quality_score",
      "store_name": "quality_score",
      "to_use": true,
      "order": 4,
      "type": "DOUBLE",
      "dft": 0.0,
      "entity_scopes": ["ITEM"]
    }
  ],
  "derivedFeatures": [
    {
      "name": "normalized_price",
      "store_name": "normalized_price",
      "type": "DOUBLE",
      "expression": "normalize(price, {\"method\":\"min_max\",\"min\":0,\"max\":1000})",
      "to_use": true,
      "output_policy": "INTERNAL_ONLY",
      "dft": 0.0,
      "order": 1000,
      "description": "供其他衍生特征使用的中间特征"
    },
    {
      "name": "price_score",
      "store_name": "price_score",
      "type": "DOUBLE",
      "expression": "multiply(normalized_price, quality_score)",
      "to_use": true,
      "output_policy": "OUTPUT",
      "dft": 0.0,
      "order": 1001
    }
  ],
  "feature_set_name": "test_001",
  "version": "latest"
}
```

### 4.1 原始特征字段

参与引擎行为的字段：

| JSON 字段 | 必填性 | 语义与内部映射 |
|---|---|---|
| `name` | 必填 | DAG 内唯一名称，映射到 `FeatureDefinition.name`。表达式只使用此名称引用特征。 |
| `raw_name` | 必填 | `generate` 输入 Map 中的字段名，映射到 `sourceBinding`。 |
| `type` | 必填 | 映射到 `DataType`。支持 `INT`、`BIGINT`、`DOUBLE`、`STRING`、`BOOLEAN`、`OBJECT`、`EVENT_SEQUENCE` 和 `UNKNOWN`。 |
| `dft` | 可选 | 输入字段缺失时的默认值，按 `type` 校验或转换。显式 JSON `null` 表示没有非空默认值。 |
| `entity_scopes` | 在线必需 | 可包含 `USER`、`SCENE`、`ITEM`；也可由 `InitOptions.rawFeatureScopes` 提供或覆盖。 |
| `to_use` | 可选 | 缺省为 `true`。`false` 的定义在构图前排除。 |
| `order` | 可选 | 用于稳定配置和输出顺序，不决定 DAG 执行顺序。缺省按 JSON 数组顺序。 |
| `store_name` | 可选 | 原始特征默认不输出；字段作为业务元数据保留。缺省为 `name`。 |

`catalog`、`encode`、`regexp`、`is_train_feature`、`featureCategory`、`table_name`、`dcs_cluster_name`、`feature_type`、`feature_group_name`、`feature_source`、`prim_keys`、`discrete_type` 和 `is_feedback` 等字段属于业务配置，不进入 DAG 核心模型。配置对象允许并保留未识别的业务字段。布尔型业务字段兼容 JSON 布尔值以及大小写不敏感的字符串 `"true"`/`"false"`。

`feature_type: "sparse"` 是业务存储/编码元数据，不是计算类型；计算类型始终来自 `type`。

### 4.2 衍生特征字段

| JSON 字段 | 必填性 | 语义与内部映射 |
|---|---|---|
| `name` | 必填 | DAG 内唯一名称，映射到 `FeatureDefinition.name`。 |
| `expression` | 必填 | 映射到 `expressionContent`，是依赖关系的唯一来源。 |
| `type` | 必填 | 声明结果类型；初始化时与算子推导类型校验。 |
| `output_policy` | 必填 | `OUTPUT` 或 `INTERNAL_ONLY`。 |
| `store_name` | 可选 | 公共结果 Map 中的键；缺省为 `name`。所有最终目标的 `store_name` 必须唯一。 |
| `to_use` | 可选 | 缺省为 `true`。禁用特征不能成为启用目标的依赖。 |
| `order` | 可选 | 默认目标选择和输出 Map 的稳定顺序；缺省按 JSON 数组顺序。 |
| `dft` | 可选 | 作为保留元数据解析，但本次不用于计算异常兜底。 |
| `description` | 可选 | 说明信息，映射到 `FeatureDefinition.description`。 |

配置不提供独立 `dependencies` 字段，避免它与表达式形成两套可能冲突的依赖定义。

## 5. 中间态特征语义

中间衍生特征使用 `output_policy: "INTERNAL_ONLY"`，最终衍生特征使用 `output_policy: "OUTPUT"`：

```text
raw_price → price → normalized_price (INTERNAL_ONLY)
                            ↓
quality_score ───────→ price_score (OUTPUT)
```

初始化 `price_score` 时，`LogicalDagBuilder` 从表达式引用自动递归构建 `normalized_price` 和所有原始依赖。中间特征进入逻辑 DAG 与物理计划，但因为不是目标根节点，不出现在 `generate` 结果中。

配置适配器将所有衍生定义映射为现有 `FeatureRole.DERIVED`，并只使用 `OutputPolicy` 表达是否对外输出，避免 `FeatureRole.INTERMEDIATE` 与 `OutputPolicy.INTERNAL_ONLY` 形成重复配置语义。现有 `FeatureRole.INTERMEDIATE` 保留以兼容内部代码。

## 6. 配置层与核心层边界

新增配置层：

```text
JSON
  ↓ Jackson
FeatureSetConfig / RawFeatureConfig / DerivedFeatureConfig
  ↓ FeatureConfigMapper
FeatureDefinition + FeatureOutputDescriptor
  ↓
LogicalDag → OptimizedLogicalPlan → PhysicalPlan
```

字段映射：

```text
JSON name           → FeatureDefinition.name
JSON type           → FeatureDefinition.dataType
JSON raw_name       → FeatureDefinition.sourceBinding
JSON dft            → FeatureDefinition.defaultValue
JSON expression     → FeatureDefinition.expressionContent
JSON output_policy  → FeatureDefinition.outputPolicy
JSON entity_scopes  → FeatureDefinition.entityScopes
JSON to_use=false   → 映射前排除
JSON store_name     → FeatureOutputDescriptor.storeName
JSON order          → FeatureOutputDescriptor.order
其他业务字段         → 配置 DTO 扩展属性，不进入 DAG 节点
```

核心 `FeatureDefinition` 保持小型，业务存储、训练和编码元数据不加入逻辑或物理节点。

## 7. InitOptions

`InitOptions` 使用 Builder 构建，至少包含：

```java
InitOptions options = InitOptions.builder()
        .environment(ExecutionEnvironment.ONLINE)
        .planId("feature-set-test-001-latest-online")
        .targetFeatures(Set.of("price_score"))
        .rawFeatureScopes(Map.of(
                "price", Set.of(EntityScope.ITEM),
                "quality_score", Set.of(EntityScope.ITEM)))
        .build();
```

同时提供仅用于默认目标和无 scope 覆盖场景的便捷工厂；需要补充目标或 scope 时使用完整 Builder：

```java
public static InitOptions offline(String planId);

public static InitOptions online(String planId);
```

字段规则：

- `environment` 必填，只允许 `OFFLINE` 或 `ONLINE`。
- `planId` 可选；缺省值为 `<trimmed-feature_set_name>-<trimmed-version>-<lowercase-environment>`。
- `targetFeatures` 可选。空集合表示选择所有 `to_use=true` 且 `output_policy=OUTPUT` 的衍生特征，按 `order` 和 JSON 数组顺序排序。
- 显式目标必须存在、启用且为 `OUTPUT` 衍生特征；不能通过显式目标泄露 `INTERNAL_ONLY` 特征。
- `rawFeatureScopes` 可选，键使用逻辑 `name`，值覆盖 JSON `entity_scopes`。
- 在线模式中，目标依赖闭包内的每个启用原始特征必须最终具有非空 scope；初始化在缺失时失败。
- 离线模式中，缺少 scope 的原始特征使用 `USER` 作为统一行级 scope；这只影响当前 planner 的分层元数据，不改变输入读取方式。

## 8. GenerateRequest 与 GenerateResult

请求采用普通 Java 类和 Java 集合，确保 Java 与 Scala 调用边界不包含 Spark 类型。

```java
public interface GenerateRequest {
    String executionId();
}

public final class OfflineGenerateRequest implements GenerateRequest {
    public OfflineGenerateRequest(String executionId, Map<String, Object> rowValues);
    public Map<String, Object> rowValues();
}

public final class OnlineGenerateRequest implements GenerateRequest {
    public OnlineGenerateRequest(
            String executionId,
            Map<String, Object> sharedValues,
            List<Map<String, Object>> candidates);
    public Map<String, Object> sharedValues();
    public List<Map<String, Object>> candidates();
}
```

请求构造时对外部集合做防御性拷贝。在线请求必须至少包含一个候选。

统一结果：

```java
public final class GenerateResult {
    public String executionId();
    public Map<String, Object> featureValues();
    public List<Map<String, Object>> candidateFeatureValues();
}
```

- 离线结果的所有目标放入 `featureValues`，`candidateFeatureValues` 为空列表。
- 在线结果中，请求级标量目标放入 `featureValues`。
- 在线候选级向量按输入候选下标转置为 `candidateFeatureValues`，列表长度与输入候选数相同。
- 所有结果键使用目标的 `store_name`。
- 结果 Map 按目标的 `order` 和配置数组顺序稳定排列。
- 公共结果不返回 `ValueHandle`、`CandidateVectorValue`、`SequenceBlock` 或 `SequenceView`。

## 9. 外部值物化

`ExternalValueMaterializer` 负责把内部值转换成普通 Java 值：

- 标量数字、字符串、布尔值和普通 Map/List 递归防御性复制。
- `SequenceBlock` 与 `SequenceView` 物化为 `List<Map<String, Object>>`。
- 每个序列事件 Map 使用稳定字段：`itemId`、`industryId`、`timestamp`、`eventType`、`value`。
- `CandidateVectorValue` 不作为一个整体暴露；在线结果将其逐候选转置，离线模式若意外产生候选向量则视为计划/上下文错误。
- `IndexValue` 不是合法的目标输出；尝试物化时失败。

## 10. 初始化流程与校验

```text
读取 JSON
  → Jackson DTO 解析
  → 顶层与字段校验
  → 过滤 to_use=false
  → scope 覆盖和默认值类型转换
  → FeatureDefinition/FeatureOutputDescriptor 映射
  → 选择目标
  → LogicalDagBuilder 构建依赖闭包
  → LogicalDagOptimizer 分析
  → PhysicalPlanner 生成环境专属计划
  → 构造不可变 FeatureDagEngine
```

初始化必须检测：

- 空文件、非法 JSON、缺失顶层数组或没有可用衍生目标。
- 原始特征和衍生特征之间的重复 `name`。
- 重复最终 `store_name`。
- 必填字符串为空、非法枚举或与 `type` 不兼容的 `dft`。
- 显式目标不存在、禁用或为 `INTERNAL_ONLY`。
- 启用衍生特征引用不存在或禁用的特征。
- 表达式解析错误、未知算子、参数数量错误和声明/推导类型不匹配。
- 循环依赖。
- 在线目标依赖的原始特征 scope 缺失。

JSON 未识别业务字段不触发错误；与 DAG 行为直接相关的已知字段必须严格校验。

## 11. 执行流程与错误处理

```text
GenerateRequest 模式校验
  → 创建独立 ExecutionContext
  → DagRuntime.execute
  → 按目标描述重命名、排序和物化
  → GenerateResult
```

错误类型：

- `FeatureDagInitializationException`：配置读取/解析、字段映射、目标选择、构图、优化或规划错误。
- `FeatureGenerationException`：请求模式不匹配、必需原始输入缺失、算子失败、候选向量长度不一致或结果无法物化。

异常消息和可读取属性包含可用的 `featureSetName`、`version`、`planId`、`executionId` 和特征名，并保留原始 cause。初始化和执行均 fail-fast。

原始输入取值规则：

1. 使用 `raw_name` 在相应共享/候选 Map 中读取。
2. 字段不存在时使用 `dft`。
3. 字段不存在且没有非空默认值时失败。
4. 字段显式存在且值为 `null` 时按显式值处理；若算子不接受 null，由算子执行失败。显式 null 不等同于字段缺失。

衍生计算失败不使用 `derivedFeatures[].dft` 自动兜底，确保离线和在线行为一致且错误可见。

## 12. Spark/Scala 与在线 Java 集成

Spark 采用 partition 级初始化：

```scala
val configBroadcast = spark.sparkContext.broadcast(configJson)

dataset.mapPartitions { rows =>
  val engine = FeatureDagEngine.init(
    configBroadcast.value,
    InitOptions.offline("offline-plan-v1")
  )

  rows.map { row =>
    val result = engine.generate(
      new OfflineGenerateRequest(row.getAs[String]("id"), rowValues(row))
    )
    result.featureValues()
  }
}
```

在线 Java 在进程启动时初始化一次，并将不可变 engine 注入请求处理器。每个请求构造新的 `OnlineGenerateRequest`。两种环境都运行在 Java 21 JVM 上。

## 13. 依赖与打包

- Maven 增加 Jackson Databind 及其传递依赖用于 JSON 解析。
- 公共 API 不出现 Jackson 类型。
- Maven Shade Plugin 同时产出普通 thin JAR 和带分类名的自包含 shaded JAR。
- shaded JAR 内部重定位 Jackson 包，降低 Spark 或在线容器已有 Jackson 版本产生冲突的风险。
- 更新 `scripts/run-demo.sh` 和 `scripts/run-self-test.sh`，使其通过 Maven 解析运行时 classpath；不再假设生产代码零依赖。
- 保持 Java 编译目标为 21，不增加 Scala 或 Spark 依赖。

## 14. 文件边界

新增公共 API 包：

```text
src/main/java/com/example/featuredag/api/
  FeatureDagEngine.java
  InitOptions.java
  GenerateRequest.java
  OfflineGenerateRequest.java
  OnlineGenerateRequest.java
  GenerateResult.java
  FeatureDagInitializationException.java
  FeatureGenerationException.java
```

新增配置包：

```text
src/main/java/com/example/featuredag/config/
  FeatureSetConfig.java
  RawFeatureConfig.java
  DerivedFeatureConfig.java
  FeatureConfigLoader.java
  FeatureConfigMapper.java
  FeatureOutputDescriptor.java
  MappedFeatureSet.java
```

新增公共边界物化器：

```text
src/main/java/com/example/featuredag/runtime/ExternalValueMaterializer.java
```

按需修改现有定义、规划或运行时类，但不把 JSON DTO、Spark 类型或业务元数据加入 DAG 节点。

## 15. 测试设计

继续使用 `DagEngineSelfTest` 和 Java `assert`，测试数据确定且断言带失败说明。新增覆盖：

1. 解析现有 `features` 格式、未知业务字段以及布尔值/字符串布尔值。
2. `raw_name` 能从离线与在线输入 Map 正确取值。
3. 中间衍生特征 A 被最终特征 B 自动递归纳入依赖闭包。
4. `INTERNAL_ONLY` 的 A 被执行但不出现在公共结果。
5. 结果使用 `store_name` 并按 `order` 稳定排列。
6. 默认目标选择和显式目标子集。
7. 离线行级 `generate` 和序列结果物化。
8. 在线共享输出、候选输出、候选顺序与候选去重优化。
9. JSON scope、`InitOptions` scope 覆盖以及在线缺失 scope 错误。
10. 文件与字符串两个 `init` 重载。
11. 非法 JSON、重复名称/输出名、禁用依赖、循环依赖、模式不匹配和缺失输入。
12. 同一在线 engine 的并发调用上下文互不污染。
13. `mvn clean package` 成功生成 thin JAR 和 shaded JAR。
14. 更新后的 Demo 与 `scripts/run-self-test.sh` 成功执行。

每项生产行为遵循测试先行：先写会因对应能力缺失而失败的断言并运行确认，再实现最小代码使其通过。

## 16. 完成标准

- 同一份 JSON 可初始化离线和在线两种环境的计划。
- Spark/Scala 可在 `mapPartitions` 中只使用 Java API 与 Java 集合调用引擎。
- 在线 Java 可安全并发复用一个已初始化 engine。
- 中间衍生特征参与计算但不对外输出。
- 公共结果只包含普通 Java 值，键使用 `store_name`，顺序确定。
- 配置或执行错误具有明确上下文且不会被静默吞掉。
- 自测试、Demo、Maven 构建和 shaded JAR 打包均通过。
