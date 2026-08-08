# Aligned List Sequences and Window Index Design

## 目标

让公共 `generate` API 可以安全接收普通 Java `List` 形式的序列特征，同时保持在线候选向量语义不变。针对如下单行数据，补齐三天窗口内指定 app 点击次数所需的运行时能力：

```text
auid = "aaaa"
auid_app_time_seq = [app0, app1, app2, ...]
timestamp = [1785549653, 1785459831, 1785286488, ...]
request_time = 1785549653
target_app = app0
```

本设计还修复 `count(extractIndustry(sequenceView, key))` 在线融合忽略 `SequenceView` selection、错误统计全量底层序列的问题。

## 范围

本次实现包含：

- 普通 `List` 原始序列与在线候选向量的运行时区分；
- 同一次 `generate` 内原始序列的执行级批次身份和长度校验；
- 列表序列派生结果的批次身份传播；
- `greater_in_sequence_typed`、`list_index_typed`、`find_list_index_typed` 三个算子的 Runtime 实现；
- `SequenceView` 感知的行业索引与候选 key 计数缓存键；
- 相关自测试和 README 能力说明。

本次不增加配置字段，不引入跨请求缓存，不实现其他已注册但未执行的业务算子，也不增加 Spark 或外部缓存依赖。

## 输入与对齐契约

用户确认以下契约成立：同一次 `generate` 中，所有声明为 `ValueShape.SEQUENCE` 且以普通 `List` 提供的原始特征，都属于同一批事件，长度一致，元素索引顺序一致。

Runtime 仍做防御性校验：

- 第一个普通 List 原始序列登记本次执行的原始事件数量；
- 后续普通 List 原始序列必须具有相同长度；
- 不一致时立即抛出包含两个特征名、期望长度和实际长度的 `IllegalArgumentException`；
- `executionId` 作为本次执行的对齐身份，同一次执行产生的列表序列共享该身份；
- 过滤、查找索引和按索引切片后的派生序列长度允许变化，但继续携带相同对齐身份。

该契约保证引擎内部不会把来自不同 `generate` 的索引用于当前序列。调用方仍负责确保放入同一个请求的原始序列在业务上确实来自同一批事件。

## 运行时值模型

新增 `ListSequenceValue`，实现 `ValueHandle`：

```java
public final class ListSequenceValue implements ValueHandle {
    public ListSequenceValue(String alignmentId, List<?> values);
    public String alignmentId();
    public int size();
    public List<Object> values();
    @Override public ValueShape shape();
    @Override public Object raw();
}
```

构造器对输入 List 做防御性复制并保留 `null` 元素；`shape()` 返回 `SEQUENCE`，`raw()` 返回不可变 List，供现有算子 evaluator 消费。

`ValueHandle` 的 sealed permits 列表加入该类型，公共输出物化器将其递归物化为普通不可变 List。

### 包装规则

Source 执行按逻辑 shape 和实体范围选择容器：

- 在线 `ITEM` scope 仍从候选 Map 收集值并产生 `CandidateVectorValue`；
- `ValueShape.SEQUENCE + 普通 List` 产生 `ListSequenceValue`；
- 已经是 `ValueHandle` 的输入保持原对象；
- `ValueShape.OBJECT + List` 继续作为 `ScalarValue` 中的不透明对象；
- 标量不再因为 Java 类型恰好是 List 而自动变成候选向量。

通用算子仅在输入句柄包含 `CandidateVectorValue` 时逐候选向量化。没有候选向量时，`ListSequenceValue.raw()` 作为完整 List 一次传给 evaluator。算子输出的逻辑 shape 为 `SEQUENCE` 且 evaluator 返回 List 时，Runtime 以当前执行的 `executionId` 包装为新的 `ListSequenceValue`。

## 三天窗口表达式

目标特征使用如下表达式，无需先构造完整的 `app -> count` Map：

```text
count(
  find_list_index_typed(
    list_index_typed(
      auid_app_time_seq,
      greater_in_sequence_typed(
        timestamp,
        request_time,
        {"margin": 259200}
      )
    ),
    target_app
  )
)
```

示例时间戳单位是秒，因此三天为 `259200`。毫秒时间戳由调用方传入 `259200000`，算子不自动猜测单位。

### `greater_in_sequence_typed(sequence, base, config)`

- `sequence` 必须是普通数值 List；
- `base` 必须是 `Number`；
- `config` 必须是 Map，且 `margin` 必须是大于等于零的 `Number`；
- 计算阈值 `base - margin`；
- 按原始顺序返回所有满足 `element > threshold` 的整数索引；
- 严格遵循需求中的“大于三天前时间戳”，恰好等于阈值的元素不入选；
- 非数值元素和 `null` 元素视为无效输入，异常消息包含元素下标；
- 返回不可变 List，Runtime 将其包装为 `ListSequenceValue`。

### `list_index_typed(sequence, indices)`

- `sequence` 必须是普通 List；
- `indices` 必须是由整数数值组成的普通 List；
- 按 indices 给出的顺序抽取元素，保留重复索引和 `null` 元素；
- 小数、负数或越界索引立即失败，异常消息包含索引位置和值；
- 返回不可变 List，批次身份由 Runtime 传播。

### `find_list_index_typed(sequence, target)`

- `sequence` 必须是普通 List；
- 使用 `Objects.equals(element, target)` 判断相等，因此支持查找 `null`；
- 按原始顺序返回所有匹配位置；
- 返回不可变整数 List，批次身份由 Runtime 传播。

现有 `count` evaluator 已支持 `Collection`，因此最终输出继续是 `INT + SCALAR`。单行离线公共结果为标量 count；调用方若需要 `[count]`，在外部协议边界包装，不改变引擎内部特征 shape。

## SequenceView 感知的在线融合

`SequenceIndustryIndex.build` 改为接收 `SequenceValue`，遍历逻辑位置：

```java
for (int logicalIndex = 0; logicalIndex < sequence.size(); logicalIndex++) {
    int baseIndex = sequence.baseIndexAt(logicalIndex);
    // 只把当前 SequenceValue 可见的 baseIndex 加入索引
}
```

`COUNT_INDUSTRY_BATCH` 不再先丢弃 selection、只保留 `baseBlock`。索引和 count 都以收到的具体 `SequenceValue` 为计算边界。

缓存键改为内部强类型 key：

- 行业索引 key 包含具体 `SequenceValue` 实例；
- 候选 count key 包含 `physicalNodeId`、具体 `SequenceValue` 实例和行业值；
- `SequenceBlock` 与不同 `SequenceView` 不共享 selection 相关缓存；
- 同一 DAG slot 重用的同一个 View 实例仍可在单次执行内命中缓存。

这比禁用融合更合适：它同时保证正确性并保留候选 key 去重、批量计算和请求内索引复用。

## 缓存边界

本次不扩大缓存生命周期。实际运行缓存仍位于 `ExecutionContext`，只在单次 `generate` 内有效：

- 序列行业索引；
- 某物理计数节点、某具体序列视图、某行业的 count。

`resultSlots` 继续保存单次执行的 DAG 节点结果。`FeatureDagEngine` 继续复用初始化时构建的物理计划。`REQUEST`、`USER_GROUP`、`PARTITION` 等其他 `CachePolicy` 仍只是规划元数据，不在本次实现中扩展为跨请求缓存。

## 错误处理

新增错误必须尽早暴露输入语义问题，并包含定位信息：

- 原始序列长度不一致：特征名、期望长度、实际长度；
- 时间窗口配置错误：算子名和 `margin`；
- 时间序列非数值元素：算子名和元素下标；
- 索引不是整数或越界：算子名、索引位置和值；
- 列表算子收到非 List：算子名和实际 Java 类型。

## 测试策略

在 `DagEngineSelfTest` 中按 TDD 增加确定性覆盖：

1. 普通 List 原始序列经 `count` 得到完整 List 长度，而不是候选逐元素执行；
2. 两条普通 List 原始序列的运行时句柄都是 `ListSequenceValue`，且 alignmentId 相同；
3. 两条原始序列长度不一致时生成失败；
4. 三个新增 evaluator 分别覆盖正常、空序列、重复值、阈值边界、非法 margin、非法元素和越界索引；
5. 完整三天窗口表达式对示例数据产生预期 app count；
6. 在线 ITEM 候选仍产生 `CandidateVectorValue`，现有在线公共 API 测试保持通过；
7. 融合计数以 `SequenceView` 为边界，只统计 View 中的行业；
8. 同一 `SequenceBlock` 上两个 selection 不同的 View 不共享错误索引或 count 缓存；
9. 全量现有自测试保持通过。

## 兼容性

- 配置格式不变；
- 普通标量和 `OBJECT` List 行为保持不变；
- 在线候选输入格式不变；
- 现有 `SequenceBlock`/`SequenceView` 公共行为保持不变；
- 原先把 `SEQUENCE + List` 误当作候选向量的行为被有意修正；
- 没有外部运行时依赖，仍使用 Java 21。
