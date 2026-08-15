# 系统技术架构总览

本文面向第一次接触本仓库的开发者，系统性介绍 Feature DAG Engine 的整体技术架构：每一层解决什么问题、
核心类型是什么、层与层之间如何协作，以及贯穿全链路的关键设计约束。更细粒度的专题设计见文末「延伸阅读」。

## 1. 项目定位

这是一个三层特征表达式 DAG 引擎的参考实现（Java 21）：业务方以声明式表达式描述特征，引擎将其编译为
**不可变逻辑 DAG**，交给**只读规划器**分析出优化事实，再由**物理层**生成一份**在构建期就完全确定**的执行计划，
最终由**运行时**逐节点执行、产出结果。

设计上最核心的一条原则贯穿所有层：**决策与执行分离**。哪些节点要融合、用什么 Kernel、走 Single 还是
Batch、缓存不缓存——这些问题全部在“编译期”（init 阶段）回答完毕，写入不可变的物理计划；运行时只是这份
计划的“解释执行器”，不做任何临时决策，也不允许出现任何与具体业务算子名绑定的分支逻辑。这也是仓库贡献指南
`AGENTS.md` 中 C1–C10 十条约束的核心意图。

## 2. 总体分层与数据流

```text
 ┌───────────┐   ┌────────────┐   ┌──────────┐
 │ definition│   │ expression │   │  config  │        L0：输入契约（互不依赖，均不依赖下游）
 └─────┬─────┘   └─────┬──────┘   └────┬─────┘
       │               │               │
       └───────────────┼───────────────┘
                        ▼
                 ┌─────────────┐
                 │   logical   │                       L1：逻辑 DAG 构建（目标驱动、逆向展开）
                 └──────┬──────┘
                        │ LogicalDag（不可变快照）
                        ▼
                 ┌─────────────┐
                 │  planning   │                       L2a：只读规划分析
                 └──────┬──────┘
                        │ OptimizedLogicalPlan（附加只读元数据）
                        ▼
                 ┌─────────────┐
                 │  physical   │                       L2b：物理计划生成（融合改写、Kernel 选择、缓存策略）
                 └──────┬──────┘
                        │ PhysicalPlan（不可变，slot:N 连接）
                        ▼
                 ┌─────────────┐
                 │   runtime   │                       L3：按物理计划执行
                 └──────┬──────┘
                        │ ExecutionResult
                        ▼
                 ┌─────────────┐
                 │     api     │                       对外入口：init / generate
                 └─────────────┘
```

`operator` 是横切层：`definition/logical/planning/physical/runtime` 都通过它访问算子的语义、类型推断
与求值能力，但 `operator` 本身只依赖逻辑语义类型，不反向依赖物理或运行时。

依赖方向严格单向：**definition/expression/config → logical → planning/physical → runtime**。规划层
和物理层永远不修改逻辑节点；运行时永远不重新做规划或物理决策。这条单向依赖链（C1）是整个仓库最基础的
架构护栏。

## 3. L0：输入契约层

### 3.1 `definition` —— 特征定义

`FeatureDefinition`（`definition/FeatureDefinition.java`）是三层构建的输入契约：**构造后不可变**，
所有校验在构造器内一次性完成，绝不产出“半成品”定义。

- **RAW 特征**：必须声明 `entityScopes`（USER / ITEM / SCENE 等实体域），且不能携带表达式；对应
  运行期的原始输入值。
- **DERIVED 特征**：必须携带表达式内容，其最终类型/形状/实体域由逻辑层从表达式推断得到，而不是在
  定义层声明——定义层只做“声明值与推断值一致性”的事后校验（对 DOUBLE 声明 / INT 推断这一种情况显式放宽）。

配套类型：`DataType`（INT/DOUBLE/STRING/BOOLEAN/OBJECT/EVENT_SEQUENCE 等）、`EntityScope`、`ValueShape`
（SCALAR/SEQUENCE/INDEX/OBJECT）、`FeatureRole`、`OutputPolicy`。这些类型小而不可变，是全链路类型系统的
词汇表。

### 3.2 `expression` —— 表达式 AST 与解析

`ExpressionParser` 把特征表达式字符串解析为临时 AST（`AstCall`/`AstFeatureRef`/`AstLiteral`/
`AstArrayLiteral`/`AstObjectLiteral`）。**这棵 AST 只在逻辑 DAG 构建期间存在**，构建完成后即被丢弃，
不进入任何持久化的计划模型——这是一个刻意的架构决策：表达式语法本身可以演进，但不会污染规划层和运行时
协议。

### 3.3 `config` —— JSON 配置加载与映射

`FeatureConfigLoader` 加载 JSON 配置为 `FeatureSetConfig`；`FeatureConfigMapper` 是关键的**适配器**：
把面向使用方的宽松配置模型（默认值、字符串枚举、实体域覆盖、输出顺序等）收敛为引擎内部严格的
`FeatureDefinition` 模型。它只做引用完整性预检（比如表达式引用的特征名是否存在），真正的类型/形状推断
仍然完全留给逻辑层完成，从而不破坏 C1 的单向依赖。

## 4. L1：逻辑层 —— `logical`

`LogicalDagBuilder`（`logical/LogicalDagBuilder.java`）是这一层的核心，职责是把「一组特征定义 + 一批
目标特征名」编译成一份**不可变**的 `LogicalDag`。关键机制：

**目标驱动、逆向展开**：只从 `targetFeatures` 出发做 DFS，递归展开其依赖闭包，未被目标特征引用到的
定义完全不进入 DAG——这保证了逻辑 DAG 始终是“最小必要子图”。

**环检测（C4）**：展开过程中用三色标记（`VisitState.VISITING`/`VISITED`）识别特征间的依赖环，一旦
遇到仍在展开栈内的特征立即抛出 `DagBuildException` 并打印完整环路径；构建完成后再用 Kahn 拓扑排序
兜底校验一次——如果排序结果节点数少于总节点数，说明图中仍有环。

**节点去重（C5）**：所有节点按 **canonical 签名**合并——

| 节点类型 | ID 前缀 | 去重键 |
|---|---|---|
| 源节点 SourceNode | `source:` | 特征名 |
| 字面量 LiteralNode | `literal:` | 类型 + 值的规范化编码 |
| 算子节点 OperatorNode | `operator:` | 算子名 + 输入节点 ID 序列 |
| 特征输出边界 FeatureOutputNode | `feature:` | 特征名 |

字面量的规范化编码用长度前缀的帧编码（`frame(tag, payload)`）避免字符串拼接产生的歧义，Map 类型的
字面量还会按键排序，使键的迭代顺序不影响去重结果。

**命名参数归一化**：表达式可以用命名参数调用算子，但这只存在于临时 AST 层；`bindNamedArguments` 在
构图前把命名参数按算子元数据声明的 `parameterNames()` 归一化为位置参数，使等价的命名/位置写法共享同一
个 canonical 节点，规划层和运行时协议完全看不到“命名参数”这个概念。

**声明与推断一致性校验（C6）**：算子节点的输出类型/实体域/值形状由 `OperatorRegistry.infer(...)` 基于
输入节点推断得出；`FeatureOutputNode` 落地前会用 `validateDeclaredType`/`validateDeclaredShapeAndScopes`
比对特征声明与推断结果，唯一放宽的例外是“声明 DOUBLE、推断 INT”——因为这种场景下特征输出节点仍需要对外
承诺 DOUBLE，供运行时定宽和下游算子按声明类型推断。

产物 `LogicalDag`（`logical/LogicalDag.java`）是节点表、根输出集合、特征名到输出节点 ID 的映射，以及
拓扑序的**不可变快照**，作为规划层和物理层的唯一输入。逻辑节点本身（`AbstractLogicalNode` 及其子类
`SourceNode`/`LiteralNode`/`OperatorNode`/`FeatureOutputNode`）之间通过 `NodeInput` 引用节点 ID 与端口
（C7），不持有对象引用，也不可被后续阶段修改。

## 5. L2a：规划层 —— `planning`（只读分析）

`LogicalDagOptimizer.analyze()`（`planning/LogicalDagOptimizer.java`）是规划层的唯一入口，职责边界
非常克制：**只读遍历逻辑 DAG，产出外置的只读元数据，绝不回写逻辑节点**（C8）。这保证逻辑节点模型始终
保持“小而语义化”，不会因为规划需要而膨胀。

每个逻辑节点对应一份 `NodePlanningMetadata`：

| 字段 | 含义 | 用途 |
|---|---|---|
| `referenceCount` | 被多少条输入边引用 | 融合安全性判断的关键依据——被消费节点必须恰好被引用 1 次 |
| `reachableRootNodeIds` | 从该节点能到达哪些根特征 | 判断节点在各输出路径上的价值与缓存范围 |
| `cacheEligible` | 是否允许被缓存 | 直接来自算子声明 `deterministic() && sideEffectFree()` |
| `estimatedSizeBytes` | 规模估算 | 物化/规模参考 |

两个算法值得展开：

- **引用计数**：一次遍历统计每条 `NodeInput` 边即可。
- **可达根集合**（`computeReachableRoots`）：如果对每个根各自反向遍历一次共享祖先子图，最坏情况下
  会有大量重复访问。这里的实现利用拓扑序做**一次反向传播**：拓扑序保证生产者在前、消费者在后，反向
  遍历时每个节点在自身及其全部消费者贡献的可达根集合确定后，才把这个集合并入它的输入节点，从而把
  「按根重复遍历」压成「按节点各访问一次」。输出结果本身最坏情况是 Θ(根数 × 节点数)——这是精确产出
  该结果的算法的下界，实现优化的是遍历过程的常数因子，而不是试图突破这个下界。

产物 `OptimizedLogicalPlan` = `LogicalDag` + `PlannerMetadata`（按节点 ID 索引的只读元数据表），是
物理层做融合/缓存决策的输入。

## 6. L2b：物理层 —— `physical`

物理层把「逻辑 DAG + 规划元数据 + 执行环境（OFFLINE/ONLINE）」转换成一份**在构建期完全确定**的
`PhysicalPlan`（C9/C10）：每个未被融合的逻辑节点对应恰好一个物理输出槽（`slot:N`），运行时只需要按槽
连线读写。

### 6.1 融合改写：`physical/rewrite`

`PhysicalRewriteRule` 是可注册的只读 DAG 模式规则接口——**规则只产出改写描述，不修改逻辑节点**：

```java
public interface PhysicalRewriteRule {
    String ruleId();
    int priority();
    Optional<PhysicalRewrite> match(
            OptimizedLogicalPlan optimized, String rootNodeId,
            ExecutionEnvironment environment, OperatorRegistry operatorRegistry);
}
```

典型场景：`keyedFilter(seq, key)` 之后紧跟 `count(结果)`，按通用路径要先把过滤后的序列整体物化、
再遍历一遍计数；但这本质上是同一个算法——建一次 key 索引、直接查 count。`CountAfterKeyedSequenceFilterRule`
就是识别这种 DAG 模式并替换为专用执行器（如 `SequenceKeyCountExecutor`）的具体规则。

融合的安全约束由规划元数据保证：被消费的中间节点必须**非根**且**引用计数恰好为 1**（否则说明它还被
其他地方共享，融合会改变可观察结果）；双方还必须都满足 `cacheEligible`。`PhysicalRewriteRegistry.select()`
让每条规则对每个节点尝试匹配，按 priority/收益/拓扑序/ruleId 仲裁冲突，保证选中的改写集合两两不重叠。

链路全景：

```text
LogicalDag → LogicalDagOptimizer.analyze() → NodePlanningMetadata
           → PhysicalRewriteRegistry.select() → Map<rootNodeId, PhysicalRewrite>
           → PhysicalPlanner.plan() → PhysicalPlan（融合节点是 ExecutorType.SPECIALIZED）
```

### 6.2 通用物理节点生成：`PhysicalPlanner`

`PhysicalPlanner.plan()`（`physical/PhysicalPlanner.java`）按逻辑拓扑序遍历：命中融合规则的节点生成
一个 `SPECIALIZED` 物理节点（只连接模式外部的依赖槽，模式内部的边由专用执行器一次性处理，且记录全部
`consumedNodeIds`）；未命中的节点按类型生成通用物理节点：

- `SourceNode` → `ExecutorType.SOURCE_BINDING`，携带源绑定名、默认值、实体域；
- `LiteralNode` → `ExecutorType.LITERAL`，携带字面量值；
- `OperatorNode` → `ExecutorType.GENERIC_OPERATOR`，携带 `singleKernelId`/`batchKernelId`/
  `batchKernelKind`（NATIVE 或 SCALAR_ADAPTER）/`invocationPolicy`/`sequenceViewInputMode`——这些全部
  是**注册能力在构建期的固化结果**，运行时不再重新探测；
- `FeatureOutputNode` → `ExecutorType.FEATURE_OUTPUT`，是否需要整型定宽到 DOUBLE（`widenIntegralToDouble`）
  在这里就已经决定，运行时只执行这个布尔策略，不解析类型。

同一个方法里还固化了三类关键策略（C10 的具体体现，全部只依据环境、实体域、算子语义与注册能力推导，
**绝不按算子名字特判**）：

- **执行阶段**（`ExecutionStage`）：OFFLINE 固定为 `OFFLINE_BATCH`；ONLINE 下依赖 ITEM 实体域的节点
  走 `CANDIDATE_BATCH`，其余走 `REQUEST_SHARED`。
- **执行模式**（`ExecutionMode`）：OFFLINE 固定 `BATCH`；ONLINE 下 `REQUEST_SHARED` 走 `REQUEST`，
  否则 `BATCH`。
- **缓存策略**（`CachePolicy`）：非确定性或有副作用的节点在规划期已被 `cacheEligible=false` 排除；
  ONLINE 的 `REQUEST_SHARED` 节点标记 `CachePolicy.REQUEST`（当前运行时一期仅记录计划意图，不消费）。
- **物化策略**（`MaterializationPolicy`）：序列形状保留视图（`VIEW`，共享底层块和选择范围），其余
  延迟物化到输出边界（`LAZY`）。

产物 `PhysicalPlan`（`physical/PhysicalPlan.java`）是不可变的物理节点列表 + 特征名到输出槽的映射，
是运行时唯一的执行依据。

## 7. L3：运行时 —— `runtime`

`DagRuntime.execute(plan, context)`（`runtime/DagRuntime.java`）严格按物理拓扑序逐节点执行，每个节点
只写入计划分配的唯一输出槽，绝不临时改变执行器、阶段、模式或缓存策略（C10）：

```java
ValueHandle result = switch (node.executorType()) {
    case SOURCE_BINDING  -> executeSource(node, context);
    case LITERAL          -> wrap(...);
    case FEATURE_OUTPUT    -> widenIntegralFeatureOutput(...);
    case GENERIC_OPERATOR  -> executeGenericOperator(node, context, state);
    case SPECIALIZED       -> executorRegistry.require(node.executorId()).execute(node, context, state);
};
```

### 7.1 值句柄体系：`ValueHandle`

```java
public sealed interface ValueHandle
        permits ScalarValue, CandidateVectorValue, OfflineBatchValue,
                RequestBatchValue, CandidateBatchValue,
                SequenceValue, IndexValue, ListSequenceValue { ... }
```

`sealed` 接口把运行态值句柄的所有分支穷举清楚：`ScalarValue`/`SequenceValue`/`IndexValue` 是单值容器，
与逻辑层 `ValueShape` 一一对应；`OfflineBatchValue`/`RequestBatchValue`/`CandidateBatchValue` 是三种
**批执行外层容器**（离线批行、在线请求组、在线候选），它们的 `shape()` 返回的是单个元素的逻辑形状，
`raw()` 把底层值暴露给算子求值。这套穷举类型体系配合 `switch` 表达式，使新增值形状时编译器会强制
所有处理分支都跟上。

### 7.2 单值/批量双执行契约

逻辑 DAG 里的算子只表达**单值语义**，运行时批维度完全在 `ValueShape` 之外。每个算子必须实现
`SingleOperatorKernel`（单值求值语义基准），可以选择性实现 `BatchOperatorKernel`（原生批量）；
未实现原生 Batch 的算子由框架提供的 `SingleLoopBatchOperatorKernel` 逐行适配，因此新增算子无需
复制两套实现。Batch Kernel 必须满足：

```text
batch(arguments)[i] == single(arguments[i])   // 逐行等价
```

同时保持行数不变、顺序不变、零行返回零行；批内错误必须包装为 `BatchOperatorEvaluationException` 并
携带行号；Kernel 实例必须无请求状态、可并发复用。

运行时按物理计划已经固化的 `invocationPolicy = SINGLE_OR_BATCH_BY_INPUT_DOMAIN` 分派：无批值输入
调用 Single Kernel；出现 `OfflineBatchValue`/`RequestBatchValue`/`CandidateVectorValue`/
`CandidateBatchValue` 输入时调用计划声明的 Batch Kernel——这是对输入载体类型的固定分派，不是运行时
重新做物理改写。首期 8 个业务算子中只有 `find_indices`、`count_distinct`、`zip_concat`、
`calc_delta_seq` 提供原生 Batch（批内按 identity 键复用收益显著，经过实测验证）；其余算子实测显示
批开销会反噬复用收益，统一走 `SCALAR_ADAPTER` 逐行适配。新增算子默认也不提供原生 Batch，必须先按
「每行可省计算量 × 批内重复度」的成本模型评估。

### 7.3 序列视图与零拷贝

`sequenceViewInputMode`（`DIRECT` 或 `MATERIALIZE`）由算子声明的 `supportsSequenceView()` 推导并
固化在物理计划中：`DIRECT` 允许 Single/Native Batch Kernel 直接消费 `OperatorSequence` 视图；
`MATERIALIZE` 则由运行时在 Kernel 边界把视图按逻辑范围转换成只读 `List`。同一 group 内重复出现的
具体视图按对象身份（identity）只物化一次，`SequenceValue` 系列类型采用**身份语义**而非值相等语义
做缓存键,这是刻意的设计选择——序列可能很大，按值比较代价过高。

### 7.4 在线分组批执行

在线请求支持一次调用携带多个相互独立的 `OnlineRequestGroup`（各自的 USER/SCENE 共享输入 + 独立的
ITEM candidates）。运行时把各组 candidate 展平为一份扁平表，同时维护组边界（`groupOffsets`）完成
「group → candidate 区间」「展平下标 → group 下标」「展平下标 → 组内下标」三种映射，整批只遍历一次
物理计划，同时保持每组的共享值广播、candidate 顺序、缓存边界和错误定位互不串扰——不做跨 group 的
状态共享。

### 7.5 可观测性

`RuntimeObserver` 在公共 `generate` 边界之外输出诊断快照，核心执行链路不写日志，指标系统也不能反向
影响 DAG 执行。默认是 `RuntimeObserver.NOOP`，此时不构造任何诊断对象，核心路径只多一次空判断。生产
场景使用 `AsyncRuntimeObserver` + 可热更新的 `RuntimeObservabilityController`：采样率、失败请求强制
采集、慢请求阈值、观测详细级别都可以在不重建引擎的情况下原子替换；请求线程只做非阻塞 `offer`，队列满
或关闭时只增加 drop 计数，不回压 DAG 执行。

## 8. 算子层 —— `operator`

`operator` 包定义了算子的完整协议，是逻辑层推断、物理层融合决策、运行时求值三方共同依赖的契约：

- `OperatorSemantic`：算子声明自己的逻辑语义（如 `SequenceCardinalitySemantic`、
  `KeyedSequenceFilterSemantic`），**不引用任何物理或运行时类型**——这是融合规则能够“认语义、不认名字”
  匹配 DAG 模式的基础。
- `OperatorDefinition`：单个算子的完整契约（继承 `SingleOperatorKernel`），包括参数个数范围、
  是否确定性（`deterministic()`）、是否无副作用（`sideEffectFree()`，**默认 false**，必须显式声明
  才能进入缓存资格判断）、是否支持序列视图直传、是否支持命名参数/链式调用等。
- `OperatorRegistry`：只负责装配、查找、参数校验和 Kernel 路由，**不承载任何业务逻辑**。
  `OperatorRegistry.standard()` 直接注册 `InitialBusinessOperators` 维护的唯一显式清单，不额外增加
  转发聚合层。
- `operator/builtin`：每个内置算子独立一个 `.java` 文件，各自实现自己的元数据、类型/形状推断与单值
  求值（如 `DiscreteOperator`、`FindIndicesOperator`、`AddOperator`、`DivOperator` 等），`InitialBusinessOperators`
  是唯一的显式算子清单。

首期标准注册表共 16 个算子：8 个业务算子（`discrete`/`log_base`/`slice_by_indices`/`find_indices`/
`get_seq_length`/`count_distinct`/`zip_concat`/`calc_delta_seq`）+ 数值转换/极值/算术算子（`to_int`/
`to_bigint`/`min`/`max`/`add`/`sub`/`mul`/`div`）。业务方也可以通过 `InitOptions.operatorExtensions()`
注册扩展算子，与标准算子共享同一套注册、推断与执行路径。

## 9. 对外入口 —— `api`

`FeatureDagEngine`（`api/FeatureDagEngine.java`）是唯一的公共入口，两阶段使用：

**`init(config, options)`**：按「定义 → 逻辑 → 规划 → 物理 → 运行时」逐层构建，各层产物依次作为下一层
输入；在引擎发布前就调用 `executors.validate(plan)` 校验全部专用 `executorId` 与配置，**让初始化失败
而不是首个请求失败**。各扩展点（算子注册表、改写规则注册表、序列索引注册表、专用执行器注册表）在这一
阶段被同一组实例共享，保证逻辑推断、物理改写和运行执行看到一致的能力集合。

**`generate(request)` / `generateBatch(request)`**：按引擎环境（OFFLINE/ONLINE）路由到对应的解码 →
运行时执行 → 编码三阶段，每个阶段都被计时并汇总进可选的执行诊断快照；支持四种请求形态——离线单行
（`OfflineGenerateRequest`）、离线批（`OfflineBatchGenerateRequest`）、在线单请求
（`OnlineGenerateRequest`）、在线分组批（`OnlineBatchGenerateRequest`）——各自对应专门的结果值句柄
（`OfflineBatchValue`/`RequestBatchValue`/`CandidateBatchValue`/`CandidateVectorValue`），由 API 层
负责把扁平化的运行时结果正确地重新映射回调用方传入的行/组/候选结构。

## 10. Demo 层 —— `demo`

`demo` 包提供仅覆盖首期 8 个业务算子的可运行调测入口（`ScalarOperatorsDemo`/`SequenceOperatorsDemo`/
`OfflineBatchOperatorsDemo`），全部通过公共 `FeatureDagEngine.init/generate` API 调用，共用同一份
`src/main/resources/demo/initial-operators.json` 配置。Demo 不含 `Main-Class`（不进打包产物），需要
通过 `scripts/run-initial-operator-demos.sh`（或 `.ps1`）或 IDE 直接运行。

## 11. JDK 版本分层约束

整个仓库构建基线是 **Java 21**（`maven.compiler.release=21`），但首期 8 个业务算子、它们在
`operator.builtin` 中共用的支撑代码，以及首期 Demo，**只允许使用 JDK 1.8 兼容的语法与标准库 API**
（禁止 `record`、文本块、模式匹配 `instanceof`、`List.of/copyOf`、`Stream.toList`、
`List.getFirst/getLast` 等）。这是为了保证这部分算子源码可以被独立抽取或代码生成，**不代表整个仓库
可以在 JDK 1.8 下编译**——`logical`/`planning`/`physical`/`runtime` 等核心层大量使用了 `record`、
`switch` 表达式、模式匹配等 Java 21 特性（本文引用的代码片段中即可看到）。

## 12. 测试与构建

- `mvn clean package`：Java 21 编译，产出 thin JAR 和包含 Jackson 依赖的
  `target/feature-dag-engine-1.0.0-SNAPSHOT-all.jar`（均为库文件，不含 Demo `Main-Class`）。
- 两种测试风格并存：遗留自测（`*SelfTest.java`，如 `DagEngineSelfTest`）用纯 Java `assert`，只能通过
  `java -ea` 运行，被 surefire 显式排除；新增测试一律是 JUnit 4（`org.junit.Test`），独立
  `*Test.java` 文件，由 `mvn test` 自动执行。
- `bash scripts/run-self-test.sh` 是**提交前必须运行**的统一入口：先以 `java -ea` 跑遗留自测，再跑
  `mvn test`，两者都通过才算绿。

## 13. 核心设计原则回顾

1. **单向分层依赖**（C1）：definition/expression/config → logical → planning/physical → runtime，
   规划层和运行时永不回写/重新决策上游产物。
2. **构造即校验、构建后不可变**（C2/C7）：`FeatureDefinition`、`LogicalDag`、`PhysicalPlan` 都是
   一次性构造、之后只读的快照。
3. **目标驱动 + 环检测 + 节点去重**（C3/C4/C5）：逻辑 DAG 只包含目标特征的必要依赖闭包，构建期即
   杜绝环和重复计算。
4. **规划元数据外置**（C8）：优化事实（引用计数、可达根、缓存资格、规模估算）不污染逻辑节点模型。
5. **决策在编译期固化，运行时只执行**（C9/C10）：融合、执行阶段、执行模式、缓存策略、Single/Batch
   路由，全部在物理计划生成时确定；核心层严禁按业务算子名字特判，扩展通过注册协议
   （`PhysicalRewriteRule`/`PhysicalExecutorRegistry`/`OperatorDefinition`）完成。

## 延伸阅读

- `docs/architecture/physical-node-fusion.md` —— 物理节点融合机制详解
- `docs/architecture/operator-single-batch-execution.md` —— 算子 Single/Batch 双执行契约
- `docs/architecture/online-grouped-batch-execution.md` —— 在线分组批执行设计
- `docs/architecture/runtime-observability.md` —— 运行时观测闭环
- `docs/architecture/operator-optimization-extension.md` —— 算子扩展规范
- `docs/architecture/sequence-view-operator-support.md` —— 序列视图算子支持
- `AGENTS.md` —— 权威贡献指南（C1–C10 约束的原始定义）
