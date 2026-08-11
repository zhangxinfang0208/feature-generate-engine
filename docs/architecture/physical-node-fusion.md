# 物理节点融合讲解

本文讲解本仓库「物理节点融合」的实现机制：融合发生在哪里、规则如何匹配、冲突如何仲裁、
物理节点如何生成、运行时如何执行，以及如何扩展一条新的融合。目标读者是第一次接触本模块的开发者。

关联文档：`docs/architecture/operator-optimization-extension.md` 是扩展规范（本文的「怎么做」），
本文侧重「为什么这样设计、代码怎么走通」。

## 1. 融合是什么、解决什么问题

逻辑 DAG 是「一个算子一个节点」的细粒度表示。如果每个逻辑节点都单独物化并执行一遍，会带来
不必要的中间结果和重复计算。例如：

```text
extractIndustry(user_seq, item_industry)   // 按 key 过滤序列
count(上述结果)                              // 统计过滤后的元素个数
```

按通用路径执行，`extractIndustry` 要先把过滤后的序列整体物化出来，`count` 再遍历一遍计数。
但「按 key 等值过滤序列 + 计数」本质上是同一个算法：**建一次 key 索引，直接查 count**。
物理节点融合就是把这个模式识别出来，替换成一个专用执行器，中间结果不再物化。

融合的设计约束（AGENTS.md 中的 C8/C9/C10）：

- 规划层只读分析，不修改逻辑节点（C8）；
- 被融合的中间节点必须非根且引用计数为 1（C9）；
- 融合后的执行策略（执行器、阶段、模式、缓存）构建期全部固化，运行时不得临时决策（C10）；
- 核心类禁止按业务算子名判断（C10），融合只认注册的 DAG 模式规则。

## 2. 融合链路全景

```text
              逻辑 DAG（LogicalDag，C5 已按 canonical 去重）
                          │
                          ▼
 ┌────────────────────────────────────────────────┐
 │ LogicalDagOptimizer.analyze()        （C8）     │
 │  为每个逻辑节点计算只读规划事实：                │
 │  引用计数 / 可达根 / 依赖维度 / 缓存资格 / 成本   │
 └────────────────────────────────────────────────┘
                          │ NodePlanningMetadata
                          ▼
 ┌────────────────────────────────────────────────┐
 │ PhysicalRewriteRegistry.select()               │
 │  每条规则对每个节点调用 match() 收集候选        │
 │  按 priority/benefit/拓扑序/ruleId 仲裁          │
 │  已消费节点不得被再次消费（互不重叠）            │
 └────────────────────────────────────────────────┘
                          │ Map<rootNodeId, PhysicalRewrite>
                          ▼
 ┌────────────────────────────────────────────────┐
 │ PhysicalPlanner.plan()                 （C9）   │
 │  跳过被消费的中间节点，为融合根生成一个         │
 │  SPECIALIZED 物理节点，槽位替换下游引用         │
 └────────────────────────────────────────────────┘
                          │ PhysicalPlan（slot:N 连接）
                          ▼
 ┌────────────────────────────────────────────────┐
 │ DagRuntime 执行                                 │
 │  SPECIALIZED 按 executorId 路由到注册执行器     │
 │  如 SequenceKeyCountExecutor（索引 + 去重计数）  │
 └────────────────────────────────────────────────┘
```

融合决策的每个环节都只依赖「算子声明过的语义 + 规划事实」，不依赖算子叫什么名字。

## 3. 前置：规划事实（C8）

`LogicalDagOptimizer.analyze()`（`planning/LogicalDagOptimizer.java:39`）只读遍历逻辑 DAG，
为每个节点产出 `NodePlanningMetadata`（`planning/NodePlanningMetadata.java:11`）：

| 字段 | 含义 | 融合中的作用 |
|---|---|---|
| `referenceCount` | 下游引用该节点的次数 | 被消费节点必须 == 1 |
| `reachableRootNodeIds` | 从该节点可到达的逻辑根 | 被消费节点不得是根 |
| `cacheEligible` | 是否可缓存 | 融合后要建索引/缓存，双方都必须可缓存 |
| `estimatedCost` | 估算执行成本 | 冲突仲裁时比较收益 |
| `dependencyDimensions` | 依赖的实体维度 | 判断 ONLINE 候选依赖 |

其中 `cacheEligible` 直接来自算子声明：`deterministic && sideEffectFree`
（`LogicalDagOptimizer.java:60`）。因此非确定性算子天然不可能被融合。

## 4. 规则层：匹配与改写描述

### 4.1 三个类型

- `PhysicalRewriteRule`（`physical/rewrite/PhysicalRewriteRule.java:10`）：只读接口，`match()`
  对「某个根节点」尝试匹配，命中则返回 `Optional<PhysicalRewrite>`，**不修改任何逻辑节点**；
- `PhysicalRewrite`（`physical/rewrite/PhysicalRewrite.java:15`）：一次已匹配的改写描述，
  本质是融合的「合同」：

  ```java
  record PhysicalRewrite(
      String ruleId,                    // 规则标识
      int priority,                     // 规则优先级
      long estimatedBenefit,            // 估算收益（供仲裁）
      String rootNodeId,                // 融合后的代表节点（输出语义跟随它）
      List<String> consumedNodeIds,     // 全部被消费节点（含 root），必须包含 root
      List<String> externalInputNodeIds,// 融合节点的外部输入
      String executorId,                // 专用执行器 ID
      ExecutionStage / ExecutionMode / CachePolicy / MaterializationPolicy,
      Map<String, Object> executorConfig)
  ```

  构造器强制校验 `consumedNodeIds` 必须包含 `rootNodeId`（`PhysicalRewrite.java:40`）；
- `PhysicalRewriteRegistry`（`physical/rewrite/PhysicalRewriteRegistry.java:17`）：规则注册表，
  标准注册表当前只含一条规则 `CountAfterKeyedSequenceFilterRule`
  （`PhysicalRewriteRegistry.java:64`）。

### 4.2 一个具体规则：CountAfterKeyedSequenceFilterRule

它匹配的模式（`physical/rewrite/CountAfterKeyedSequenceFilterRule.java:26`）：

```text
SequenceCardinality(              ← 根节点，如 count
    KeyedSequenceFilter(          ← 被消费节点，如 extractIndustry
        sequence, candidateKey
    )
)
```

匹配流程（`match()`，第 33-109 行）：

1. **环境检查**：只接受 ONLINE（第 38 行）；
2. **语义检查**：根节点必须声明 `SequenceCardinalitySemantic`（第 44-49 行）；
3. **结构检查**：聚合输入要么直接是过滤算子，要么是包了一层 `FeatureOutputNode` 的过滤算子
   （第 51-64 行）。允许「特征输出中转」意味着**可观察或共享的中间结果不会被融合**——如果一个
   过滤序列被声明成独立特征且是根，`FeatureOutputNode` 会被排除；
4. **过滤语义检查**：过滤算子必须声明 `KeyedSequenceFilterSemantic`，并给出序列输入下标、
   key 输入下标（第 66-73 行）；
5. **安全与资格检查**（第 75-82 行）：
   - 聚合、过滤节点都必须 `cacheEligible`；
   - `safeToConsume`（第 111-114 行）：被消费节点必须**非根**且**引用计数 == 1**；
6. **实体域检查**：key 输入节点必须依赖 ITEM 实体域（第 86-88 行）——这是 ONLINE 候选批
   语义的一部分；
7. **产出合同**：consumed = [filter, (featureOutput), aggregate]，external inputs =
   [sequence, key]，executorId = `sequence-key-count`（`PhysicalExecutorIds`），
   stage=CANDIDATE_BATCH、mode=CANDIDATE_KEY、cache=CANDIDATE_KEY，
   config 携带 `keyDomain`（第 89-108 行）。

收益估算 = 过滤节点成本 + 聚合节点成本（第 94-95 行），用于多规则冲突仲裁。

### 4.3 冲突仲裁（互不重叠）

`PhysicalRewriteRegistry.select()`（`PhysicalRewriteRegistry.java:29`）：

1. 对每个拓扑序节点 × 每条规则收集候选；
2. 按 **priority 降序 → estimatedBenefit 降序 → root 拓扑序 → ruleId 字典序** 排序
   （第 44-48 行）；
3. 贪心挑选：候选的 `consumedNodeIds` 与已接受集合有任何重叠即跳过（第 50-57 行）；
4. 最终结果按 root 拓扑序排序后返回 `Map<rootNodeId, PhysicalRewrite>`（第 57-61 行）。

这保证了一个逻辑节点至多被一条规则消费，多条规则共存时行为确定、可复现。

## 5. 物理转换：槽位替换（C9）

`PhysicalPlanner.plan()`（`physical/PhysicalPlanner.java:51`）是融合真正落地的地方。

### 5.1 第一步：收集被跳过节点

```java
Set<String> skippedLogicalNodes = new LinkedHashSet<>();
for (PhysicalRewrite rewrite : rewrites.values()) {
    for (String consumedNodeId : rewrite.consumedNodeIds()) {
        if (!consumedNodeId.equals(rewrite.rootNodeId())) {
            skippedLogicalNodes.add(consumedNodeId);
        }
    }
}
```

（`PhysicalPlanner.java:55-62`）被消费的中间节点在物化时被「吃掉」，不再单独生成物理节点。

### 5.2 第二步：按拓扑序转换

遍历逻辑拓扑序（第 70-115 行）：

- `skippedLogicalNodes` 中的节点直接 `continue`（第 71 行）；
- 每个保留节点分配一个新输出槽 `slot:N`（第 74 行）；
- **有 rewrite 的节点**（第 76-94 行）生成融合物理节点：

  ```java
  new PhysicalNode(
      "physical:" + sequence + ":specialized",
      rewrite.consumedNodeIds(),          // 记录全部被融合的逻辑节点
      ExecutorType.SPECIALIZED,
      rewrite.executorId(),               // sequence-key-count
      rewrite.executionStage(),           // CANDIDATE_BATCH
      rewrite.executionMode(),            // CANDIDATE_KEY
      logicalNode.valueShape(),
      inputSlots,                         // ← external inputs 的槽位
      outputSlot,
      rewrite.cachePolicy(),              // CANDIDATE_KEY
      rewrite.materializationPolicy(),
      config)                             // 含 rewriteRuleId + keyDomain
  ```

  注意输入槽位来自 `rewrite.externalInputNodeIds()`（第 77-79 行）——被消费的中间节点
  没有自己的 slot，外部输入直接成为融合节点的输入；
- **无 rewrite 的节点**走 `createGenericPhysicalNode`（第 126-181 行），按节点类型映射
  SOURCE_BINDING / LITERAL / GENERIC_OPERATOR / FEATURE_OUTPUT 执行器；
- 槽位表 `logicalSlots` 维护「逻辑节点 ID → 输出槽」映射（第 108 行），下游节点引用
  被自动替换为上游实际输出槽；
- 逻辑根对应的 `FeatureOutputNode` 记录到 `outputFeatureSlots`（第 111-115 行），最后
  校验每个逻辑根都有物理输出槽（第 117-121 行）。

### 5.3 融合前后对照

以 `ExampleFeatures` 中真实存在的模式为例（`demo/ExampleFeatures.java:30-36`）：
`extractIndustry(user_seq1, item_industry)` → `same_industry_seq` → `count(same_industry_seq)`
→ `same_industry_count`。假设该特征为 ONLINE 目标特征：

```text
融合前（通用路径）                              融合后
─────────────                                   ─────────────
source:user_seq1  → slot:1                     source:user_seq1     → slot:1
source:item_industry → slot:2                  source:item_industry → slot:2
operator:extractIndustry → slot:3              physical:N:specialized → slot:3
  input [slot:1, slot:2]                         input [slot:1, slot:2]   ← 直接吃外部输入
operator:count → slot:4                          consumed: [extractIndustry, count]
  input [slot:3]
feature:same_industry_count → slot:5
  input [slot:4]
```

`extractIndustry` 与 `count` 两个逻辑节点合并为一个物理节点，中间序列结果
（slot:3 原本的完整过滤序列）不再物化，下游直接消费融合节点的计数输出。

## 6. 运行时执行

`PhysicalExecutorRegistry.validate(plan)` 在计划执行前 fail-fast 校验所有 SPECIALIZED 节点
的配置与 Provider（`PhysicalExecutorRegistry.java:38-41`）；`DagRuntime` 只按 executorId
路由（`DagRuntime.java:82`），不新增任何业务分支。

融合执行器 `SequenceKeyCountExecutor`（`runtime/SequenceKeyCountExecutor.java:15`）把
「过滤 + 计数」实现为**单遍索引 + 去重计数**：

1. **取索引**：从 `SequenceIndexRegistry` 按 `keyDomain`（config 中）取 `SequenceIndexProvider`；
2. **归一化 key**：对每个候选 key 调用 `provider.normalizeQueryKey`，然后去重
   （`state.setDedupCounts` 记录去重前后数量，供观测）；
3. **建索引（带缓存）**：以 `(groupIndex, keyDomain, 具体 SequenceValue)` 为 key 查
   `RuntimeCache`，未命中才 `provider.build(sequence)`（第 68-81 行）；
4. **计数（带缓存）**：对每个去重后的 key 以 `(groupIndex, keyDomain, SequenceValue, key)`
   为 key 查缓存，未命中调用 `index.count(key)`（第 83-97 行）；
5. **映射回候选顺序**：按归一化后的原始 key 顺序输出结果向量（第 99-101 行）。

该执行器同时支持离线候选向量路径（`CandidateVectorValue`）和在线分组批路径
（`CandidateBatchValue`，第 104-179 行，按 `groupIndex` 分别建索引、计数）。

## 7. 安全性小结：为什么这样融合不会错

| 风险 | 防护手段 | 位置 |
|---|---|---|
| 中间结果被别处使用，融合后丢了 | 被消费节点引用计数必须 == 1 | `safeToConsume` |
| 中间结果是输出根，必须可观察 | 被消费节点不得是根 | `safeToConsume` |
| 非确定性/副作用算子结果被缓存复用 | 双方必须 `cacheEligible`（deterministic + sideEffectFree） | `LogicalDagOptimizer.java:60` |
| 语义冒充（名字像但行为不同） | 只认注册的 `OperatorSemantic`，不认算子名 | `CountAfterKeyedSequenceFilterRule.java:44-73` |
| 多条规则冲突、重复消费 | 仲裁排序 + 已消费集合排除 | `PhysicalRewriteRegistry.java:50-57` |
| 索引/执行器缺失导致运行期才炸 | 计划执行前 `validate` fail-fast | `PhysicalExecutorRegistry.java:38-41` |
| 运行时临时改策略 | 阶段/模式/缓存全部固化在 PhysicalNode | C10，`PhysicalPlanner.java:122` |
| 缓存 key 碰撞 | key 覆盖 groupIndex + keyDomain + 具体 SequenceValue（+ key） | `SequenceKeyCountExecutor.java:16-25` |

## 8. 如何新增一条融合

1. 为算子实现正确的类型推断与通用求值器，普通路径先能跑（`operator` 包）；
2. 在 `OperatorDefinition` 声明确定性、无副作用、成本，并注册 `OperatorSemantic`
   （如 `KeyedSequenceFilterSemantic`、`SequenceCardinalitySemantic`）；
3. 先看现有规则能否匹配新模式，能匹配就不要新增规则；
4. 确需新规则时实现 `PhysicalRewriteRule`，注册到 `PhysicalRewriteRegistry`，
   在 `match()` 里检查：环境、语义、非根、引用计数 1、外部输入齐全；
5. 新算法时在 `PhysicalExecutorIds` 加 executorId、实现 `PhysicalExecutor` 并注册到
   `PhysicalExecutorRegistry`；新索引结构时注册 `SequenceIndexProvider`；
6. 补充测试：语义别名可匹配、无语义不匹配、ONLINE/OFFLINE、根与共享节点安全、
   FeatureOutput 中转、空序列/空候选/重复 key、缓存隔离、fail-fast；
7. 更新本文档与扩展规范（`operator-optimization-extension.md`）及 C1–C10 相关注解。

## 9. 关键代码索引

| 环节 | 文件 | 关键行 |
|---|---|---|
| 规划事实 | `planning/LogicalDagOptimizer.java` | `analyze()` :39 |
| 规划事实载体 | `planning/NodePlanningMetadata.java` | :11 |
| 规则接口 | `physical/rewrite/PhysicalRewriteRule.java` | :10 |
| 改写描述 | `physical/rewrite/PhysicalRewrite.java` | :15 |
| 规则注册与仲裁 | `physical/rewrite/PhysicalRewriteRegistry.java` | `select()` :29 |
| 现有融合规则 | `physical/rewrite/CountAfterKeyedSequenceFilterRule.java` | `match()` :33 |
| 物理转换 | `physical/PhysicalPlanner.java` | `plan()` :51 |
| 物理节点 | `physical/PhysicalNode.java` | :16 |
| 执行器路由 | `runtime/DagRuntime.java` | :82 |
| 融合执行器 | `runtime/SequenceKeyCountExecutor.java` | :15 |
