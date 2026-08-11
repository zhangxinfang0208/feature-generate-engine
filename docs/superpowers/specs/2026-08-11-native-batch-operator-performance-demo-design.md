# 首期算子 Native Batch 与复杂性能 Demo 设计

## 目标

为首期 8 个标准算子实现逐行等价的 Native `BatchOperatorKernel`，并新增一个可调数据规模的复杂在线 Batch Demo。Demo 同时验证深层嵌套表达式的 canonical 公共子表达式合并、Native Batch 路由、逐请求与分组 Batch 的输出一致性，并报告性能数据。

本次不补充物理融合语义，不修改物理改写规则，也不要求产生融合物理节点。Demo 必须断言 `fusedPhysicalNodeCount == 0`，避免把 Native Batch 与物理融合混为一谈。

## 范围与约束

- 标准注册表仍严格只包含 `discrete`、`log_base`、`slice_by_indices`、`find_indices`、`get_seq_length`、`count_distinct`、`zip_concat`、`calc_delta_seq`。
- 每个业务算子的 Single、Native Batch、推断和元数据实现继续位于各自独立 `.java` 文件中；注册类只装配实例。
- `OperatorDefinition` 的 Single Kernel 是语义基准；Native Batch 必须保持行数、顺序、输出值和逐行异常语义。
- Kernel 实例不得保存请求状态，所有批内索引和复用表只存在于一次 `evaluateBatch` 调用中。
- 首期算子、直接共用的 `operator.builtin` 支撑代码和 Demo 只使用 JDK 8 可用的语言特性与标准库 API。
- 不修改 `OperatorRegistry`、`PhysicalPlanner` 和 `DagRuntime` 的业务路由逻辑，不按算子名在规划层或运行时增加分支。
- 保留现有三个 Demo 的输入、输出和运行入口。

## 自动注册与执行链路

每个首期算子类在继承 `AbstractBuiltinOperator` 的同时实现 `BatchOperatorKernel`。现有框架自动完成后续路由：

1. `OperatorRegistry.register()` 通过 `definition instanceof BatchOperatorKernel` 选择 Native Kernel；
2. `BatchOperatorKernel.batchKernelKind()` 默认返回 `BatchKernelKind.NATIVE`；
3. `PhysicalPlanner` 把注册能力写入物理节点的 `batchKernelKind` 配置；
4. `DagRuntime` 按计划调用 Native Kernel；
5. 节点诊断记录 `OperatorInvocationKind.BATCH_NATIVE`。

没有 Batch 域时继续执行 Single Kernel；存在离线行、在线请求组或在线候选域时执行 Native Kernel。Native Kernel 只消费已经由运行时广播对齐的 `BatchColumn`，不自行解释公共请求模型。

## 公共实现规则

每个算子把 Single 与 Batch 共用的单行计算抽取为私有方法。Native Batch 对整列做直接循环，需要跨行复用时在调用内维护局部表。每一行的计算或参数错误都包装为带原始行号的 `BatchOperatorEvaluationException`。

共享支撑代码只负责以下通用机械行为：

- Java 8 兼容的值、数值与序列读取；
- 不可变结果集合构造；
- Batch 行异常包装；
- 使用 group、对象身份和标量参数构造调用内复用键。

业务算法、参数含义和复用策略不得移入注册类或运行时层。

## 各算子的 Native Batch 策略

### `find_indices`

按 `groupIndex + 序列对象身份` 建立一次等值倒排索引，索引内容为“元素 → 不可变位置列表”。同组中不同 candidate key 直接查询索引，相同 key 复用同一不可变结果。不同 group 即使输入值相等也不得共用索引。

这把典型在线候选场景从每行扫描共享序列，降低为每组扫描一次共享序列，再按 candidate key 查询。

### `discrete`

相同边界列表对象只完成一次数值转换、有限值校验和严格递增校验。每行先保持 Single Kernel 的 value 校验语义，再使用已转换边界计算 bucket。不同边界对象分别预处理。

### `log_base`

相同 base/upbound 参数组合只完成一次有限值、取值范围校验及 `Math.log(base)` 计算。每行仍独立校验 value，并沿用 Single Kernel 的除法顺序计算结果，避免乘倒数造成末位浮点差异。

### `slice_by_indices`

按 `groupIndex + 序列对象身份 + indices 对象身份` 复用不可变切片结果。未命中的参数组合直接执行与 Single 相同的边界与整数索引校验。

### `get_seq_length`

直接按列读取 `OperatorSequence`、`Collection` 或数组长度。长度读取为 O(1)，不建立复用表，避免缓存查找成本超过计算本身。

### `count_distinct`

按 `groupIndex + 序列对象身份` 复用去重计数；首次遇到序列时使用与 Single 相同的元素遍历和相等语义计算。

### `zip_concat`

按 `groupIndex + 所有输入序列对象身份 + 配置对象的有效 delimiter` 复用不可变拼接结果。首次计算时保持序列数量、等长约束和默认分隔符语义。

### `calc_delta_seq`

按 `groupIndex + 数值序列对象身份 + 规范化 base` 复用不可变差值序列。首次计算时保持 base 和每个序列元素的有限数值校验。

## 复杂 Demo

新增独立的 Native Batch 性能 Demo、JSON 配置和 Bash/PowerShell 脚本，不把大数据压测塞入现有快速 Demo。

Demo 使用在线分组 Batch：每个 group 有独立的 USER 共享序列，每组包含多条 ITEM candidate。数据使用固定公式生成，不依赖随机种子，因此每次运行的输入分布和结果一致。

配置包含两条深表达式族，并覆盖全部 8 个算子：

- 序列表达式族：`find_indices → slice_by_indices → slice_by_indices → zip_concat → count_distinct`；两个分支复用相同的内层索引选择表达式。
- 数值表达式族：`calc_delta_seq → get_seq_length → log_base → discrete`。

每个表达式族提供两个完全相同的输出定义。逻辑 DAG 应只增加第二个 `FeatureOutputNode`，而不复制整条算子链，用于验证 canonical 公共子表达式合并。

Demo 初始化以下两个目标集合：

- 单输出计划：每个表达式族只选择一个输出；
- 重复输出计划：同时选择原输出和完全相同的 alias 输出。

执行后，重复输出计划相对单输出计划只允许增加 alias 对应的输出节点数量，算子链节点不得重复增长。

## 性能对照

性能对照只比较 Native Batch 的两种公共 API 调用方式：

1. 逐 group 调用 `generate(OnlineGenerateRequest)`；
2. 一次调用 `generateBatch(OnlineBatchGenerateRequest)`。

两条路径使用相同引擎配置和相同输入，初始化时间不计入测量。Demo 先预热，再进行多轮测量，输出输入规模、预热轮数、测量轮数、总 candidate 数、墙钟耗时、吞吐量，以及诊断中的 decode/runtime/encode 耗时。

默认参数保证开发机可以在合理时间内完成；序列长度、group 数、每组 candidate 数、预热轮数和测量轮数均可由脚本参数覆盖。Demo 不设置绝对耗时或加速比断言，因为这类阈值会受 JIT、GC、CPU 和系统负载影响。

## 正确性与诊断断言

Demo 和自测试必须执行以下硬断言：

- 逐 group 与分组 Batch 的每个 group、每个 candidate 输出完全一致且顺序不变；
- alias 输出与原输出完全一致；
- 8 个标准算子的注册能力均为 `BatchKernelKind.NATIVE`；
- 复杂计划中的通用算子节点通过诊断记录为 `BATCH_NATIVE`；
- Native Batch 的结果逐行等于直接调用 Single Kernel 的结果；
- Batch 失败保留准确的原始行号；
- `fusedPhysicalNodeCount == 0`；
- 重复输出计划只增加输出节点，不复制公共算子链。

`find_indices` 额外使用可计数的共享序列测试替身，验证同组重复行只扫描一次序列；不同 group 必须各自扫描，证明复用边界没有跨组污染。

## 错误处理

- 任一 Native Kernel 的某行失败时抛出 `BatchOperatorEvaluationException(rowIndex, cause)`；公共 API 继续由现有运行时补充 group/candidate 位置。
- 预处理缓存首次建立失败时，错误归属当前触发建立的行；失败结果不写入复用表。
- Native Kernel 返回列数与输入行数不一致时，继续由 `OperatorRegistry` 的现有校验拒绝。
- 复杂 Demo 在任何结构或结果断言失败时以非零退出码结束，不继续输出误导性的性能结论。

## 文件边界

预计修改：

- `src/main/java/com/example/featuredag/operator/builtin/` 下 8 个首期算子文件；
- `src/main/java/com/example/featuredag/operator/builtin/OperatorSupport.java`；
- `src/test/java/com/example/featuredag/DagEngineSelfTest.java`。

预计新增：

- 一个独立复杂 Native Batch Demo Java 入口；
- 一个独立 Demo JSON 配置资源；
- 独立的 Bash 和 PowerShell 性能运行脚本；
- 必要的测试辅助类型只放在测试源码中。

## 验证命令

实现完成后必须显式运行：

```text
./scripts/run-self-test.sh
mvn clean package
./scripts/run-native-batch-performance-demo.sh
```

Windows 环境使用对应 PowerShell 脚本。性能 Demo 的默认运行用于功能、路由和输出格式检查，不把单次本机数字作为稳定性能基线。
