# 在线分组 Batch 执行设计

## 背景与目标

原有 `OnlineGenerateRequest` 表示“一份 USER/SCENE 共享输入 + 一组 ITEM candidates”。当调用方一次需要处理多个 user/request 时，如果把所有 candidate 直接放进同一个请求，共享值会错误广播到其他 user，序列索引与请求缓存也会失去隔离边界。

在线分组 Batch 的目标是：

- 一次公共 API 调用承载多个相互独立的在线请求组；
- 整批只遍历一次物理计划，降低节点调度、上下文和状态表创建开销；
- 保留每组 USER/SCENE 广播、candidate 顺序、缓存边界和错误定位；
- 保持原 `generate(OnlineGenerateRequest)` 的行为与返回结构不变。

第一版不提供跨 group 的状态共享、不改变物理计划结构，也不把多个 group 合并成一个业务请求。

## 公共 API

```java
OnlineRequestGroup group = new OnlineRequestGroup(
        "user-a",
        sharedValues,
        candidates);

OnlineBatchGenerateResult result = engine.generateBatch(
        new OnlineBatchGenerateRequest("batch-1", List.of(group)));
```

`OnlineRequestGroup` 包含：

- `executionId`：组级标识，用于结果关联和异常定位；
- `sharedValues`：该组的 USER/SCENE RAW 输入；
- `candidates`：仅属于该组的 ITEM RAW 输入。

`OnlineBatchGenerateResult.groupResults()` 与请求的 `groups()` 按下标一一对应。每个元素仍是原有 `GenerateResult`：

- `featureValues()` 保存该组的请求级输出；
- `candidateFeatureValues().get(i)` 保存该组第 `i` 个 candidate 的输出。

批请求允许零个 group，也允许某个 group 包含零个 candidate。

## 运行时布局

运行时把各组 candidate 展平，同时保存边界：

```text
groups:
  user-a -> [a0, a1, a2]
  user-b -> [b0, b1]
  user-c -> []

flattenedCandidates = [a0, a1, a2, b0, b1]
groupOffsets        = [0, 3, 5, 5]
candidateGroupIndex = [0, 0, 0, 1, 1]
```

`ExecutionContext` 使用 `groupOffsets` 完成以下映射：

- group → candidate 起止下标；
- 展平 candidate 下标 → group 下标；
- 展平 candidate 下标 → 组内 candidate 下标。

候选表只存一份，不在每个物理节点重新分组或复制。

## 批值句柄与广播规则

运行时新增两个外层批容器：

- `RequestBatchValue`：一个元素对应一个 group 的请求级值；
- `CandidateBatchValue`：一个元素对应一个展平后的 candidate 值。

它们的 `shape()` 返回单个元素的逻辑形状，因此不会改变逻辑 DAG 的类型/形状推断。

算子求值域由输入句柄确定：

| 输入组合 | 求值次数 | 输出句柄 | 广播规则 |
|---|---:|---|---|
| 仅普通标量/字面量 | 1 | 原有值句柄 | 输出边界按需广播 |
| `RequestBatchValue` + 标量 | group 数 | `RequestBatchValue` | 标量向所有 group 广播 |
| `CandidateBatchValue` + 标量 | candidate 总数 | `CandidateBatchValue` | 标量向所有 candidate 广播 |
| `RequestBatchValue` + `CandidateBatchValue` | candidate 总数 | `CandidateBatchValue` | request 值按 candidate 所属 group 广播 |

离线 `OfflineBatchValue`、单请求 `CandidateVectorValue` 与在线分组批值不能混用，混用会被运行时拒绝。

## Source 与输出转置

ONLINE Batch 下：

- 非 ITEM Source 从每个 group 的 `sharedValues` 读取，生成 `RequestBatchValue`；
- ITEM Source 从展平 candidate 表读取，生成 `CandidateBatchValue`；
- 默认值仍按原 Source 定义应用；
- 缺失必填值时，异常包含 `groupIndex`、组级 `executionId`，ITEM 输入还包含组内 `candidateIndex`。

执行完成后，请求级特征按 group 写入 `GenerateResult.featureValues()`；候选级特征根据 `groupOffsets` 转置回各组的 `candidateFeatureValues()`，输入顺序不会改变。

## 缓存与专用执行器隔离

批内复用的身份键（原生 Batch 的 identity 键与融合执行器的缓存键）都包含 `groupIndex`。即使不同 user 的参数值相同，也不会把一个 group 的缓存记录作为另一个 group 的命中。

`SequenceKeyCountExecutor` 按 group 执行：

1. 读取该组的 `SequenceValue`；
2. 只在该组 candidates 内进行 key 去重；
3. 构建或读取该组的 `REQUEST_INDEX`；
4. 计算该组的 key count；
5. 按该组 candidate 原顺序写入展平结果。

索引和 count 缓存键均包含 `groupIndex`，避免跨 user 污染。运行状态中的去重指标按整批汇总，但 unique 数是各组 unique 数之和。

## 分层约束

- C1/C8：逻辑 DAG 与优化元数据保持只读，不为 Batch 改写逻辑节点；
- C7：批维度只存在于运行时值句柄和 `ExecutionContext`，不进入不可变逻辑节点；
- C9：每个物理节点仍只产生一个输出槽，槽中可以承载批值句柄；
- C10：`REQUEST_SHARED`、`CANDIDATE_BATCH` 和缓存策略仍由物理计划决定，运行时只根据既定阶段传播对应批值。

## 性能与边界

设 group 数为 `G`、candidate 总数为 `C`、物理节点数为 `N`：

- 多次单请求需要约 `G × N` 次节点调度；
- 分组 Batch 只需 `N` 次节点调度；
- 实际算子业务计算仍与请求级节点的 `G`、候选级节点的 `C` 成正比。

Batch 会延长中间槽内批值的生命周期，因此调用方应同时限制 group 数和 candidate 总数。具体批大小应根据特征宽度、序列大小、延迟目标和 GC 表现压测确定，不建议把整个无界队列合并为一个 Batch。

第一版采用 fail-fast：任一 group 或 candidate 失败时整批失败，不返回部分结果。

## 测试要求

端到端测试必须覆盖：

- 多 group 且 candidate 数量不等；
- group 无 candidate、整个 Batch 无 group；
- 请求级输出与候选级输出同时存在；
- Batch 结果与逐 group 单请求结果一致；
- 默认值与缺失输入的 group/candidate 定位；
- 通用算子的 request→candidate 广播；
- 专用序列执行器的逐组索引、去重和顺序还原；
- 原单请求在线、离线单行与离线 Batch 回归。

首期仓库不提供依赖非首期算子的在线/离线 Batch Demo。调用方仍可通过
`InitOptions.targetFeatures` 触发 C3 的目标驱动构图，并使用公共 Batch API 验证自己的业务配置。
