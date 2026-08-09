# Unified Array Feature Value API Design

## 背景

在线推荐链路中的原始特征既有用户/场景标量，也有用户历史序列和逐候选物品特征。现有
`CommonFeatureTransTaskImpl` 会在 `preTransform`、`transform` 和 `buildInput` 阶段完成
hash、离散化、用户特征广播、候选特征排列以及最终 `long[]` 构建。DAG 引擎的职责位于这些
模型编码步骤之前：它根据仍具有业务含义的原始标量和序列，生成新的标量或序列特征。

旧在线数据可能使用如下字符串协议：

```text
ratings = ["1|0|1|v2"]
```

在线适配层可以在调用 DAG 引擎前完成版本校验、去除版本后缀和元素转换，向引擎提供：

```text
ratings = [1, 0, 1]
```

因此，DAG 引擎不需要理解分隔符、`v2`、`requiredPass`、`RANK_DL_OUT_KEY` 或模型
`long[]` 协议。

## 目标

第一版采用统一的外部数组载荷，同时保留内部标量、序列和候选向量语义：

- 新公共 API 的每个特征值都使用数组/List 表示；
- 配置中的 `value_shape` 决定数组在 Runtime 中解释为标量还是序列；
- 原始特征与衍生特征使用相同的值格式，二者仅生产方式不同；
- DAG 共享输出和逐候选输出都使用统一数组格式；
- DAG 在线执行位于 `CommonFeatureTransTaskImpl.preTransform` 之前；
- 下游继续负责 hash、离散化、广播、模型截断和 `long[]` 构建；
- 第一版不兼容旧的裸标量公共 API。

## 非目标

第一版明确不包含：

- 外部数组的完整 shape、长度或元素类型校验；
- `alignment_group` 配置和跨序列对齐验证；
- 旧管道字符串的解析、版本一致性校验或版本后缀处理；
- `SEQ_TRUNCATE_LEN` 模型输入截断；
- DAG 内部的 hash、离散化或模型张量构建；
- 旧 `Map<String, Object>` 标量输入兼容模式；
- DAG 节点失败后的部分结果返回或 Runtime 内降级；
- `CommonFeatureTransTaskImpl` 自身的实现改造。

## 方案比较

### 方案一：统一数组载荷，配置驱动 shape

公共请求只携带 `Map<String, List<?>>`。引擎初始化时已经从特征配置获得 `dataType`、
`valueShape` 和 `entityScopes`，请求无需重复携带这些元数据。

优点是请求简单、与当前配置驱动架构一致、改动集中在公共边界和 Runtime 包装处。缺点是
Java 泛型无法在编译期表达每个特征的具体元素类型，第一版依赖调用方遵守配置契约。

### 方案二：显式 `FeatureValue` 包装

每个请求值都携带 `dataType`、`shape` 和 `values`。该方案自描述能力更强，但会重复配置
信息，并引入“请求声明与已编译配置不一致”的冲突处理。第一版不采用。

### 方案三：根据数组长度推断 shape

将长度一解释为标量、其他长度解释为序列。该方案无法区分单元素序列、空序列和标量，
也无法表达候选轴，禁止采用。

第一版选择方案一。

## 统一特征定义模型

每个原始或衍生特征继续由相同的 `FeatureDefinition` 描述：

```text
FeatureDefinition
├── name
├── dataType
├── valueShape
├── entityScopes
└── producer
    ├── SOURCE：原始特征
    └── EXPRESSION：衍生特征
```

`dataType` 描述值或序列元素的业务类型，`valueShape` 描述值的形态。普通类型序列可以声明
为 `STRING + SEQUENCE` 或 `INT + SEQUENCE`；现有 `EVENT_SEQUENCE` 第一版不删除，继续
服务 Runtime 内部的 `SequenceBlock`/`SequenceView` 结构。新的 `FeatureDagEngine.generate`
公共请求不直接接收 `ValueHandle` 或 `SequenceBlock`；需要这些结构的底层执行和测试继续通过
`DagRuntime`/`ExecutionContext` 内部边界使用它们。公共 EVENT_SEQUENCE 输出仍物化为数组。

示例：

```text
auid                 STRING + SCALAR
target_app           STRING + SCALAR
auid_app_time_seq    STRING + SEQUENCE
timestamp            INT    + SEQUENCE
ratings              INT    + SEQUENCE
paid_cnt_3d          INT    + SCALAR
filtered_apps        STRING + SEQUENCE
```

原始与衍生只在 producer 上不同，不影响输入输出值的表示。

## 公共 API 契约

新 API 替换现有裸 `Object` 值边界，不提供兼容构造器：

```java
OfflineGenerateRequest(
        String executionId,
        Map<String, List<?>> rowValues)

OnlineGenerateRequest(
        String requestId,
        Map<String, List<?>> sharedValues,
        List<Map<String, List<?>>> candidates)

GenerateResult(
        String executionId,
        Map<String, List<?>> featureValues,
        List<Map<String, List<?>>> candidateFeatureValues)
```

由于 Java 泛型擦除，旧、新 Map 构造器不并存；第一版直接替换旧契约。输入 Map、候选列表
和其中的 List 在请求构造时执行防御性复制。复制实现必须允许 List 内出现 `null`，不能直接
使用会拒绝 null 元素的 `List.copyOf`。这里的“数组”统一指公共 Java 边界的 `List<?>`；
模型侧专用的原生 `long[]` 仍只由下游 `buildInput` 产生。

### 输入解释

引擎不根据数组长度推断 shape，只读取已编译节点的 `value_shape`：

```text
SCALAR   + [value]       -> ScalarValue(value)
SEQUENCE + [v1, v2, ...] -> ListSequenceValue([v1, v2, ...])
```

第一版信任调用方已经满足以下前置条件：

- 每个 SCALAR 数组恰好包含一个元素；
- SEQUENCE 数组已经完成业务所需的解析和对齐；
- 数组元素符合配置的 `dataType`；
- 需要通过索引联合计算的序列下标语义一致。

第一版不增加独立的输入 shape/type 校验阶段。SCALAR 解码直接读取第一个元素；空数组会在
解码时导致整次生成失败，多余元素属于调用方违反契约，第一版只消费第一个元素。缺少特征
仍沿用现有规则：存在配置默认值时使用默认值，否则生成失败。标量 null 必须表示为包含一个
null 元素的 List；Map value 本身为 null 不属于受支持的输入契约。未经适配直接传入
`["1|0|1|v2"]` 时，引擎只会把它当作一个字符串元素，不负责识别或拒绝旧协议。

### 候选轴

调用方不直接传递 `CANDIDATE_VECTOR`。候选轴由 `OnlineGenerateRequest.candidates` 的外层
列表表达：

```java
List.of(
        Map.of("category", List.of("tech")),
        Map.of("category", List.of("sports")),
        Map.of("category", List.of("tech")))
```

Runtime 按已编译 shape 解码每个候选中的数组，再把同一 ITEM source 聚合为内部
`CandidateVectorValue`。候选中的 SCALAR 成为候选向量的标量元素；候选中的 SEQUENCE
成为候选向量的序列元素。不同候选的 ITEM 序列允许具有不同长度。

### 输出编码

公共输出统一编码为数组：

```text
ScalarValue(1)                    -> [1]
ScalarValue(null)                 -> [null]
ListSequenceValue(["a", "b"])    -> ["a", "b"]
CandidateVectorValue([1, 2, 1])   -> 每个候选分别得到 [1]、[2]、[1]
```

输出编码必须使用允许 `null` 元素的不可变 List 实现，不能使用 `List.of(null)`。

### 边界适配组件

公共 API 层新增两个职责单一的适配器：

- 输入解码器在引擎初始化时取得物理计划依赖到的 source binding、scope 和 `value_shape`，
  在每次 `generate` 时只解码计划实际需要的字段；请求中的无关字段不进入 Runtime；
- 输出编码器把 `ExecutionResult` 中的 `ValueHandle` 递归物化，并保证每个最终特征值都是
  List。共享输出和候选输出使用同一套编码规则。

输入解码器产生现有 Runtime 可消费的自然内部 Map：SCALAR 对应单个 Object，SEQUENCE
对应完整 List。默认值、缺失 source 和算子执行仍由已有 DAG 执行链处理。这样 Runtime
算子不需要认识新的外部数组包装协议。

## Runtime 语义

内部继续保留专用 `ValueHandle`，不把所有值强制变成序列：

- `ScalarValue`：请求、用户、场景或单候选标量；
- `ListSequenceValue`/`SequenceValue`：事件或普通列表序列；
- `CandidateVectorValue`：在线候选轴；
- 其他现有专用句柄保持不变。

通用算子继续消费内部自然值。例如：

```text
greater_in_sequence_typed(timestamp, request_time, ...)
```

实际 evaluator 收到完整 timestamp List 和单个 request_time Number，而不是单元素 List。
`find_list_index_typed` 同样收到 target_app 字符串标量。

构图阶段继续执行算子 shape 推导以及衍生特征声明与推导结果的一致性校验。本设计所说的
“第一版不做 shape 校验”，仅指不对公共输入数组执行完整合法性校验，不删除 DAG 的静态
shape 语义。

## 序列对齐的第一版边界

第一版不增加 `alignment_group`，也不验证索引是否跨用了不相关序列。调用方负责保证表达式
中需要按下标联合的序列已经对齐。

当前 `ExecutionContext` 使用一次执行中的首条原始序列长度，强制所有后续原始 List 序列
等长。该规则必须移除，因为真实请求可以同时包含互不相关且长度不同的序列，例如：

```text
auid_app_time_seq/timestamp = 100 个 app 行为
past_items/ratings          = 30 个 HSTU 行为
```

`executionId` 可以继续作为请求内 Runtime 值身份，但第一版不利用它实施跨序列对齐校验。
算子自身已有的越界、数值参数等运行时校验继续保留。

## 在线数据流和组件边界

DAG 作为独立前置阶段接入，不把表达式执行揉进 `CommonFeatureTransTaskImpl.preTransform`：

```text
LegacyFeatureNormalizeTask
  · "1|0|1|v2" -> [1, 0, 1]
  · 完成版本一致性校验
        ↓
ResolveFeatureSources
  · 在 DAG 前应用请求/用户覆盖物品特征的规则
        ↓
FeatureDagGenerateTask
  · 调用 FeatureDagEngine.generate
  · 回填共享和候选衍生特征
        ↓
CommonFeatureTransTaskImpl
  · preTransform
  · transform
  · buildInput
        ↓
模型推理
```

覆盖规则必须在 DAG 前执行，确保 DAG 与后续编码看到相同的 source 值。旧字符串协议解析也
必须在 DAG 前完成。面向模型的 `SEQ_TRUNCATE_LEN` 保持在 `buildInput`；DAG 默认消费完整
有效序列，窗口、过滤或业务截断由表达式算子决定。

### DAG 请求

```java
new OnlineGenerateRequest(
        requestId,
        sharedValues, // USER + SCENE
        candidates);  // 按排序候选顺序排列的 ITEM Map
```

共享依赖子图只计算一次；依赖 ITEM 的节点通过现有候选向量机制按候选执行。

### 结果回填

`FeatureDagGenerateTask` 负责把结果合并到转换前的特征容器：

```text
GenerateResult.featureValues()
    -> 合并到 userFeatures/sharedFeatures

GenerateResult.candidateFeatureValues()[i]
    -> 合并到第 i 个 itemFeatures
```

候选顺序必须保持不变。DAG 输出名称不得覆盖已存在的原始特征名；发生冲突时整次任务失败。
引擎返回新 Map，不直接修改 `ExecutionSession`，由集成任务负责合并和写回。

回填后，`CommonFeatureTransTaskImpl` 按其特征配置统一处理原始与衍生特征：决定是否 hash、
是否 `requiredPass`、是否广播、以及如何构建最终 `long[]`。已经规范化为数组的序列不再进入
旧管道字符串拆分分支。

### 仓库职责边界

本仓库负责实现统一数组公共 API、schema 驱动的输入解码、内部 Runtime 执行、统一数组输出
编码、Demo 和引擎自测试。`LegacyFeatureNormalizeTask`、source 覆盖规则、
`FeatureDagGenerateTask`、`ExecutionSession` 回填以及 `CommonFeatureTransTaskImpl` 的接入位于
在线系统仓库；本仓库只定义并验证供其调用的契约，不创建这些在线组件的伪实现。

## 失败策略

第一版不返回部分 DAG 结果。初始化阶段的配置、表达式、类型或静态 shape 推导错误继续使用
`FeatureDagInitializationException`。执行阶段的以下情况导致整次生成失败：

- 缺少必需 source 且没有默认值；
- 算子 Runtime 求值失败；
- 候选输出数量与输入候选数量不一致；
- 已注册但未实现的算子进入 Runtime。

引擎继续使用 `FeatureGenerationException` 携带 `planId`、`executionId/requestId`、可确定的
`featureName` 和原始异常。是否记录转换状态、降级、跳过 DAG 或继续使用原始特征，由外部
`FeatureDagGenerateTask`/在线编排层决定。DAG 输出与原始特征的命名冲突在结果合并阶段由
`FeatureDagGenerateTask` 检测，同样阻止请求进入 `preTransform`，但不伪装成 Runtime 算子
错误。

## 测试策略

在 `DagEngineSelfTest` 及公共 API 集成覆盖中增加确定性测试：

1. 离线 SCALAR 输入 `[1785549653]` 在 Runtime 中成为 Number 标量；
2. 离线 SEQUENCE 输入 `["app0", "app1"]` 保持完整序列；
3. 三天 app 计数案例全部改为数组输入，公共输出为 `[1]`；
4. USER/SCENE 衍生特征只计算一次并进入共享数组输出；
5. ITEM 衍生特征按候选顺序进入 `candidateFeatureValues`；
6. 候选 SCALAR 输出分别包装为单元素数组；
7. 衍生 SEQUENCE 输出保持多元素数组；
8. 两条无关且长度不同的原始序列可以出现在同一次请求中；
9. 缺失 source、算子失败和候选数量不一致均整次失败；
10. 输入与输出 `[null]` 使用可容纳 null 的不可变 List；
11. 现有构图、静态 shape 推导、在线候选向量和离线执行回归保持通过。

旧字符串到数组的解析属于在线适配层，不在本仓库的 DAG Runtime 测试中伪造实现。
user/item Map 合并、输出命名冲突和后续转换读取由在线系统仓库的集成测试覆盖。

## 文档和 Demo 更新

实现时同步更新：

- `DagDemo`：`auid`、`request_time` 和 `target_app` 改为单元素 List；计数输出改为 `[1]`；
- README：公共 API 示例全部使用统一数组值；
- `AGENTS.md`：删除“auid 不要包装”的旧契约，改为新 API 只接受数组；
- 现有对齐 List 设计文档保留为历史设计，本设计覆盖其公共标量输入和全局长度校验约定。

## 兼容性

这是有意的破坏性公共 API 变更：

- 裸标量输入不再受支持；
- 标量公共输出从 `value` 改为 `[value]`；
- 在线 candidate Map 中的标量也必须使用单元素 List；
- 公共 `generate` 输入不再直接接受 `SequenceBlock`/其他 `ValueHandle`；
- 不提供兼容开关、双格式解析或弃用期；
- Runtime 内部标量/序列/候选向量语义保持分离；
- 旧在线字符串协议必须由引擎外适配层先转换。
