# 算子语义、物理改写与缓存扩展规范

本文规定特征 DAG 引擎新增算子优化、专用执行器、序列索引和缓存策略时必须遵守的扩展方式。
目标是让核心 `planning`、`physical`、`runtime` 只依赖稳定协议，不再按业务算子名称增加分支。

当前标准注册表包含首期的 `discrete`、`log_base`、`slice_by_indices`、`find_indices`、
`get_seq_length`、`count_distinct`、`zip_concat`、`calc_delta_seq`，数值转换/极值算子
`to_int`、`to_bigint`、`min`、`max`，以及算术算子 `add`、`sub`、`mul`、`div`
（`div` 固定产出 DOUBLE，分母为 0 时防除 0 返回 0.0）。每个算子必须拥有独立实现类；
注册清单不得承载推断或求值逻辑。首期 `operator.builtin` 源码还必须保持 JDK 1.8 语法/API 兼容，
但项目整体构建基线仍为 Java 21。

`group_count_concat` 是业务扩展示例，不属于上述标准清单。它通过
`InitOptions.Builder.addOperatorExtension(...)` 按引擎实例注册，同一个扩展实例集合会贯穿
逻辑推断、物理计划和运行执行，且不会修改 `OperatorRegistry.standard()` 的固定边界。

## 1. 基本原则

1. 算子名称只用于表达式解析、注册表查找和通用算子执行，不得作为规划或物理优化条件。
2. 算子在 L0 声明“它是什么”；物理规则声明“哪些 DAG 模式可以安全改写”；运行时执行器声明“具体怎么算”。
3. `LogicalDagOptimizer` 只读计算引用数、可达根、缓存资格和大小估算，不得修改逻辑 DAG（C8）。
4. 融合只能消费非根、单引用的中间节点；规则必须显式列出所有被消费节点和外部输入（C9）。
5. 执行阶段、模式、缓存和物化策略必须在物理计划构建期确定，运行时只执行计划（C10）。
6. 注册失败、执行器缺失或索引 Provider 缺失必须 fail-fast，不得静默回退为不同语义。

## 2. 分层职责

```text
OperatorDefinition + OperatorSemantic
    描述算子行为、纯度和逻辑语义
                    ↓
LogicalDagOptimizer + NodePlanningMetadata
    计算与业务名称无关的规划事实
                    ↓
PhysicalRewriteRegistry
    使用算子语义匹配 DAG 模式并产出 PhysicalRewrite
                    ↓
PhysicalPlanner + PhysicalNode
    固化 executorId、slot、stage、mode、cache 和 materialization
                    ↓
PhysicalExecutorRegistry + SequenceIndexRegistry
    执行专用算法并解析索引 Provider
```

L0 的 `OperatorSemantic` 不得引用 `PhysicalNode`、`CachePolicy`、`PhysicalExecutor` 或运行时实现。
物理层可以读取逻辑语义；运行时可以读取物理计划，禁止反向依赖。

## 3. 注册算子语义

`OperatorDefinition` 除了名称、参数数量、推断和求值函数，还提供：

- `deterministic()`：相同输入是否产生相同输出；
- `sideEffectFree()`：是否没有外部副作用（默认 false，内置算子经 `AbstractBuiltinOperator` 显式声明 true）；
- `supportsSequenceView()`：Single 与 Native Batch Kernel 是否可直接消费 `OperatorSequence`；
- `semantics()`：可被规则消费的逻辑语义列表。

当前标准语义：

- `KeyedSequenceFilterSemantic(sequenceInputIndex, keyInputIndex, keyDomain)`：按 key 等值过滤序列；
- `SequenceCardinalitySemantic(sequenceInputIndex)`：返回序列元素数量。

例如，未来扩展的序列过滤算子可以声明：

```java
new KeyedSequenceFilterSemantic(
        0,
        1,
        SequenceKeyDomains.INDUSTRY)
```

对应的序列基数算子可以声明：

```java
new SequenceCardinalitySemantic(0)
```

语义是正确性承诺。只有当算子对所有合法输入都满足该语义时才能注册；名称、参数数量或输出 shape
相似并不足以注册相同语义。

### 3.1 Single/Batch 执行能力

`OperatorDefinition` 继承 `SingleOperatorKernel`，其单值结果是算子语义基准。需要原生批量优化的
算子同时实现 `BatchOperatorKernel`；其他算子由 `SingleLoopBatchOperatorKernel` 自动适配。
Batch 输入只依赖 operator 层的 `BatchColumn`、`BatchLayout` 等只读协议，不得引用 runtime 的
`ExecutionContext` 或 `ValueHandle`（C1）。

物理计划必须记录 `singleKernelId`、`batchKernelId`、`batchKernelKind` 和
`OperatorInvocationPolicy` 枚举。运行时必须读取该枚举，可按
输入载体选择计划已声明的 Single 或 Batch Kernel，但不得在请求阶段匹配融合规则或改写节点
（C10）。详细契约见 `docs/architecture/operator-single-batch-execution.md`。

`supportsSequenceView()` 是正确性承诺。物理计划把它固化为
`sequenceViewInputMode=DIRECT|MATERIALIZE`：支持时保留零拷贝视图，不支持时由运行时按逻辑 selection
物化为只读 `List`。算子层只能依赖 `OperatorSequence`，不得引用 runtime 的 `SequenceView`；运行时
也不得按业务算子名决定输入策略。详细设计见 `docs/architecture/sequence-view-operator-support.md`。

## 4. 注册物理改写规则

所有融合规则实现 `PhysicalRewriteRule`，并注册到 `PhysicalRewriteRegistry`。规则必须：

1. 根据 `OperatorSemantic` 和 DAG 边匹配，不判断业务算子名；
2. 检查 `ExecutionEnvironment`；
3. 检查被消费节点不是根且引用数为 1；
4. 检查算子确定性和无副作用；
5. 返回 root、consumed nodes、external inputs、executorId 和完整执行策略；
6. 不修改任何 `LogicalNode`。

当前规则 `CountAfterKeyedSequenceFilterRule` 匹配：

```text
SequenceCardinality(
    KeyedSequenceFilter(sequence, candidateKey)
)
```

并生成 `executorId=sequence-key-count` 的专用节点。它既支持直接嵌套，也允许中间经过一层
`FeatureOutputNode`；可观察或共享的中间结果不会被融合。

多条规则发生冲突时，注册表按以下顺序选择：

1. `priority` 降序；
2. `estimatedBenefit` 降序；
3. root 的逻辑拓扑序；
4. `ruleId` 字典序。

已经被一条规则消费的逻辑节点不能再被另一条规则消费。

## 5. 注册专用执行器

新增专用物理算法时：

1. 在 `PhysicalExecutorIds` 增加稳定的 `executorId`；
2. 实现 `PhysicalExecutor`；
3. 注册到 `PhysicalExecutorRegistry`；
4. 由改写规则生成相同的 `executorId`；
5. 在执行前通过 `PhysicalExecutorRegistry.validate(plan)` 校验。

不得为每个业务优化向 `DagRuntime` 增加方法或 switch 分支。`DagRuntime` 只识别
`ExecutorType.SPECIALIZED`，再按 `executorId` 路由到注册执行器。

执行器的 `validate` 应校验必需 config 和下游 Provider，错误应在计划开始执行前暴露。

## 6. 注册序列索引

等值序列索引统一使用：

- `SequenceKeyDomain`：逻辑字段域，例如 `event.industry`；
- `SequenceKeyExtractor`：从 `SequenceBlock + baseIndex` 提取 key（事件为不可变 Map，按名取列）；
- `SequenceIndexProvider`：绑定 keyDomain、提取器和查询 key 归一化；
- `SequenceIndexRegistry`：按 keyDomain 注册 Provider；
- `SequenceKeyIndex`：通用构建 `key -> baseIndex[]`；
- `IndexValue`：通用索引值。

新增 category 索引时只注册 Provider：

```java
indexRegistry.register(
        new SequenceKeyDomain("event.category"),
        (block, index) -> block.columnValueAt("category", index),
        String::valueOf);
```

不得新增与算法完全相同、仅字段不同的 `SequenceCategoryIndex`、`SequenceTagIndex` 等类。
只有时间范围索引、倒排索引、空间索引等数据结构或查询语义明显不同的场景，才应新增独立 Provider 实现。

索引 key 与查询 key 必须使用同一归一化器：`SequenceIndexProvider.build` 默认在构建索引时对
extractor 结果应用 `normalizeQueryKey`，`SequenceKeyCountExecutor` 对查询 key 同样调用
`normalizeQueryKey`，保证类型与 null 语义对称（例如标准 Provider 的 `String.valueOf` 会把
字段缺失的 null 与查询 null 统一归一化为字符串 `"null"`；若需要区分缺失与 null，应由 Provider
自定义归一化规则）。

索引必须遍历 `SequenceValue` 的逻辑下标并调用 `baseIndexAt`，不得直接扫描整个 `baseBlock`，否则会破坏
`SequenceView` 的选择边界。

## 7. 缓存推导与执行

### 7.1 一次 DAG 执行内的复用

逻辑 canonical 去重和物理 slot 已保证同一个节点只计算一次。`referenceCount > 1` 用于判断共享和融合安全，
不代表应再次添加节点结果缓存。ONLINE 的 `REQUEST_SHARED` 节点只执行一次，结果保存在请求级
`ExecutionContext.resultSlots` 中；规划器为其标记 `CachePolicy.REQUEST`（预留语义），运行时通过共享
slot 自然复用，不产生真实缓存 lookup，因此不计入 `RuntimeCache` 的命中率。

### 7.2 候选批内复用

通用候选批去重路径（`CANDIDATE_KEY`）已移除：批内重复计算由原生 Batch 的 identity 键复用消除，
未提供原生 Batch 的算子逐行计算。`CachePolicy.CANDIDATE_KEY`/`ExecutionMode.CANDIDATE_KEY`
仅保留供融合改写（`CountAfterKeyedSequenceFilterRule`）标注融合执行器节点，不再由 Planner 授予
普通算子。非确定性或有副作用算子仍必须使用 `CachePolicy.NONE`。

### 7.3 索引缓存

序列索引缓存 key 至少包含：

```text
keyDomain + concrete SequenceValue
```

按 key 聚合结果至少包含：

```text
keyDomain + concrete SequenceValue + normalizedKey
```

当前使用具体 `SequenceValue` 对象身份区分不同 `SequenceView`。若未来引入跨等价视图复用，必须先为
`SequenceSelection` 定义稳定、不可冲突的内容指纹。

### 7.4 缓存生命周期

当前 `ExecutionContext` 生命周期等于一次 `generate`：

- `REQUEST` 为规划期预留标记，运行时一期不消费；融合改写节点保留 `CANDIDATE_KEY` 标注；
- 不允许跨请求持有 `SequenceValue`；
- `USER_GROUP` 需要单独的离线批上下文和明确清理边界，未实现前不得声称具有跨行缓存能力。

真实缓存访问必须通过 `ExecutionContext.runtimeCache()` 完成，以统一记录 lookup/hit/miss/put；运行时观测
契约见 `runtime-observability.md`。

## 8. 新增优化的标准流程

1. 为算子实现正确的推断和普通 Runtime evaluator；普通路径必须先可执行。
2. 注册确定性、纯度和逻辑语义。
3. 判断现有通用改写规则能否匹配；能匹配时不要新增规则。
4. 若算法等价关系不同，新增独立 `PhysicalRewriteRule`。
5. 优先复用现有专用执行器；确需新算法时注册新的 `PhysicalExecutor`。
6. 优先复用 `SequenceKeyIndex`；确需新索引结构时注册新的 `SequenceIndexProvider`。
7. 增加计划、运行时、失败边界和 ONLINE/OFFLINE 测试。
8. 更新本文档和 C1–C10 相关中文注解。

## 9. 必测边界

每个新增优化至少覆盖：

- 使用不同算子名但相同语义仍可匹配；
- 名称相似但未注册语义时不匹配；
- ONLINE/ OFFLINE 行为；
- 根节点与共享中间节点不被错误消费；
- 直接嵌套和 FeatureOutput 中转；
- 空序列、空候选、重复 key；
- SequenceView 选择范围；
- 不同 keyDomain 不发生缓存碰撞；
- 缺失 executor 或 Provider 时 fail-fast；
- 确定性算子去重，非确定性算子不缓存；
- 逻辑输出与未优化普通执行路径一致。

## 10. 禁止事项

- 禁止在 `LogicalDagOptimizer`、`PhysicalPlanner`、`DagRuntime` 中新增业务算子名判断。
- 禁止让 `OperatorSemantic` 引用物理或运行时类型。
- 禁止规则直接修改逻辑节点或逻辑拓扑。
- 禁止创建计划声明了缓存、运行时却完全不消费且未标注为预留的缓存策略。
- 禁止以候选下标代替业务输入值构造任何缓存 key，否则重复 key 无法复用。
- 禁止让索引缓存跨越其声明的请求或批次生命周期。
