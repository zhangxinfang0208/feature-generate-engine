# 算子失败使用特征默认值设计

## 背景

当前运行时按物理拓扑序执行节点。算子节点一旦抛出 `RuntimeException`，
`DagRuntime.executeNode` 会立即终止本次执行，因此后续 `FEATURE_OUTPUT` 节点没有机会读取
衍生特征的 `dft`。现有 `dft` 只替换算子成功返回后的 `null`、空字符串或空容器。

目标是让所有算子的运行期求值异常在特征配置了非空 `dft` 时可恢复，并满足以下约束：

- Single、Offline Batch、Online Candidate 和 Online Grouped Batch 语义一致；
- Batch 只替换失败行、失败候选或失败请求组，其他结果保持数量、顺序和值不变；
- 嵌套表达式中的失败跳过该求值单元的后续算子，直到特征边界应用 `dft`；
- 共享逻辑节点不得绑定某个消费者的 `dft`，每个 `FEATURE_OUTPUT` 使用自己的默认值；
- 未配置非空 `dft` 时仍抛出原始异常；
- 构图、配置、输入绑定、物理计划和运行时协议错误不得被默认值掩盖；
- 核心层不得按业务算子名称增加分支，继续满足 C1-C10。

## 非目标

- 不把 RAW 输入缺失改成算子失败；RAW 仍使用自身 `dft`，没有默认值时直接失败。
- 不捕获 `Error`，包括 `OutOfMemoryError`、`StackOverflowError` 和 `AssertionError`。
- 不为有副作用的算子提供事务回滚，也不在失败后自动重试算子。
- 不改变直接调用 `OperatorRegistry.evaluate` 或 `evaluateBatch` 的 fail-fast 契约。
- 不把解析、推断、注册、参数数量、Kernel 路由或计划不变量错误转换为特征默认值。

## 方案选择

采用“失败作为 DAG 内部值传播”的方案。算子求值成功时产生普通值；求值失败时产生只在
operator/runtime 边界内可见的失败结果。下游算子对失败的求值单元短路并传播最初失败，
`FEATURE_OUTPUT` 是唯一消费失败并应用 `dft` 的边界。

未采用以下方案：

- 为每个特征复制计算节点并注入默认值：破坏 C5 节点去重和 C9 物理槽位约束；
- API 层捕获后逐特征、逐行重算：重复执行算子，破坏缓存和副作用语义，批量性能不可控。

## 核心数据流

```text
算子正常
  -> 保存正常结果
  -> 下游继续计算

算子某个求值单元异常
  -> 保存内部 EvaluationFailure（cause + 位置 + 起始节点）
  -> 下游对同一求值单元短路，不调用 Kernel
  -> 失败传播到所属 FEATURE_OUTPUT
       |- 非空 dft：替换失败值，继续执行
       `- 无 dft：重新抛出保留 cause 和位置的异常
```

“短路”本身不终止程序。它只跳过已经失败的 Single 值、Batch 行、请求组或候选的后续算子。
只要失败最终到达一个具有非空 `dft` 的特征边界，其他求值单元和无关 DAG 分支就继续执行；
若沿途先到达没有默认值的特征边界，本次请求仍会失败。若失败经过一个带 `dft` 的中间特征
边界，失败在该边界被解析为普通默认值，下游特征随后使用该默认值正常计算。

## 内部失败模型

### Single 结果

operator 协议层增加不可变的单条求值结果类型，表达以下二选一状态：

- success：包含正常返回值，正常返回值允许为 `null`；
- failure：包含原始 `RuntimeException`。

`OperatorRegistry` 增加供 DAG 运行时使用的容错求值入口。该入口先完成算子查找和参数数量
校验，再仅围绕 `OperatorDefinition.evaluate` 建立异常边界。这样未知算子和参数数量错误仍
直接抛出，而 Kernel 内抛出的所有 `RuntimeException` 转换为 failure。

现有公开 `evaluate` 继续直接抛出 Kernel 异常。脱离特征 DAG 的调用没有可用的特征
`dft`，因此不得静默恢复。

### 运行时失败载体

runtime 层增加内部 `EvaluationFailure`，至少保存：

- 原始 `RuntimeException cause`；
- 失败起始物理节点 ID；
- 求值域；
- 可用时的 offline row、online group、group execution ID、candidate index。

Single 失败使用带逻辑 `ValueShape` 的失败句柄。Batch 外层句柄仍保持原类型，失败元素在其
值列表内使用内部失败标记。失败标记不得进入公共编码器、运行时缓存或最终特征输出。

同一求值单元有多个失败输入时，按算子参数顺序传播第一个失败，保证结果确定且保留最早的
根因；下游短路产生的节点不得覆盖起始节点和原始 cause。

## 运行时传播

### Single

`DagRuntime` 调用注册表的容错求值入口。Kernel 失败时生成失败句柄。下游 Single 算子在
调用 Kernel 前检查输入句柄；只要存在失败输入，就直接生成带当前输出 shape 的失败句柄，
不调用 Kernel。

### Batch

Batch 调用前，运行时先按原始行序检查各参数列：

- 有失败输入的行继承第一个失败，不交给 Kernel；
- 健康行通过投影视图形成新的 `BatchOperatorCall`；
- 投影后的 `BatchLayout` 把局部行映射回原始行，并保留 group/candidate 定位；
- Kernel 返回后按原始行序 scatter 成完整结果，把继承失败、Kernel 新失败和成功值合并。

这保证 Native Batch 只处理健康行，同组身份复用仍使用原 group index，不会把内部失败标记
传给业务 Kernel。结果行数和顺序始终与原求值域一致。

### Feature Output

`FEATURE_OUTPUT` 在现有空值默认处理之前识别失败：

- 非空 `dft`：Single 整体替换，Batch 按失败元素替换；
- `dft` 为 `null` 或未配置：抛出带特征名和求值位置的特征求值异常，cause 指向原始异常；
- 替换后继续执行现有 BIGINT/DOUBLE 数值定宽；
- 序列默认值继续复用现有规则：完整 List 表示完整默认序列，标量表示一个兜底元素；
- 发生至少一次替换时设置 `RuntimeNodeState.fallbackUsed`，并记录 fallback 数量。

空值与失败值保持不同语义：正常返回的 `null` 或空容器继续走既有空值默认逻辑；失败值在
没有 `dft` 时必须抛错，不能像普通 `null` 一样泄漏到输出。

### 共享节点和嵌套表达式

失败值不包含 `dft`。同一个 canonical 算子节点即使被多个特征共享，也只产生一份失败
事实；各 `FEATURE_OUTPUT` 独立决定使用自己的默认值或抛错。

对于 `add(to_int(score), 10)`，若 `to_int` 失败，`add` 对该求值单元短路，整个表达式到
特征边界后使用该特征的 `dft`。不得先把 `to_int` 替换成默认值再执行 `add`，因为 `dft`
属于特征而不是表达式内部算子。

## 异常分类

### 可由特征默认值恢复

- `OperatorDefinition.evaluate` 内抛出的所有 `RuntimeException`；
- Scalar Adapter 明确关联到某行的 Kernel 异常；
- Native Batch 通过逐行失败协议报告的异常；
- 融合执行器内可关联到 Single、row、group 或 candidate 的算子计算异常；
- 从上游算子继承的失败。

这包括标准或扩展算子抛出的 `IllegalArgumentException`、`ArithmeticException`、
`ClassCastException`、`NullPointerException` 和自定义 `RuntimeException`。选择捕获所有
`RuntimeException` 是“所有算子求值异常均可使用特征 dft”需求的直接结果。

### 必须 fail-fast

- 表达式解析、算子推断、逻辑构图和环检测错误；
- 未注册算子、参数数量错误和非法注册元数据；
- JSON 配置、`dft` 类型转换和公共 API 输入解码错误；
- RAW 输入缺失且没有 RAW 默认值；
- Kernel ID、Batch Kernel 能力或专用执行器注册错误；
- 输入槽缺失、跨域句柄混用、Batch 行数不一致和缓存类型损坏；
- 无法关联到具体求值单元的 Batch 协议异常；
- 所有 `Error`。

## Batch Kernel 协议

### Scalar Adapter

`SingleLoopBatchOperatorKernel` 改为逐行收集成功值和失败。某行抛出 `RuntimeException` 时
记录该行 failure 并继续下一行，不再用第一个 `BatchOperatorEvaluationException` 终止整批。

### Native Batch

`BatchOperatorResult` 增加不可变的逐行失败集合，同时保留现有仅传成功列的构造方式。
提供 Builder 或等价辅助 API，支持按行记录 success/failure，并在构造时校验：

- 值列长度始终等于局部 Batch 行数，失败行使用不可观察的 `null` 占位；
- 每个失败占位都在失败集合中有且只有一条记录，成功行不得出现在失败集合中；
- 失败下标在范围内且不重复；
- 返回总行数与调用行数相同。

Native Kernel 增加可选的逐行恢复能力标记。当前四个原生 Batch 算子
`find_indices`、`count_distinct`、`zip_concat`、`calc_delta_seq` 实现该能力，保持原有批内
身份复用，仅把 catch 分支从“抛出整批异常”改为“记录当前行失败并继续”。

旧式扩展 Native Kernel 若未声明逐行恢复能力，特征 DAG 的物理计划选择该算子的
`SingleLoopBatchOperatorKernel`。所有算子都必须提供 Single Kernel，因此能在不重试、不
重复副作用的前提下保证逐行 `dft` 语义。扩展实现逐行恢复能力后即可恢复 Native 路由。
规划分析在 `NodePlanningMetadata` 记录某节点是否可达至少一个配置了非空 `dft` 的特征边界；
只有需要失败恢复的路径才要求逐行恢复能力。计划只依据可达性和注册能力选择 Kernel，不按
业务算子名称特判，符合 C8/C10。

直接调用原有 `OperatorRegistry.evaluateBatch` 时，如果结果含失败，仍按最小失败行号抛出
`BatchOperatorEvaluationException`，保持脱离特征 DAG 的 fail-fast 契约。DAG 运行时使用
新的容错 Batch 入口读取完整逐行结果。

## 融合执行器兼容

融合执行器必须声明逐行失败能力：

- 支持时，返回与普通算子路径相同的内部失败表示；
- 不支持且相关路径需要特征 `dft` 恢复时，规划阶段不应用该物理改写，退回普通算子节点；
- 融合执行器仍不得读取特征默认值，默认值只在 `FEATURE_OUTPUT` 消费。

当前 `SequenceKeyCountExecutor` 补齐 group/candidate 级失败：请求序列索引构建失败影响该请求
组的相关候选；候选 key 规范化或查询失败只影响对应候选。融合前后输出、失败范围和默认值
语义必须逐行等价。

是否需要恢复同样来自 `NodePlanningMetadata` 的可达特征边界分析。物理改写规则只读取该
元数据和已注册执行器能力，不改写逻辑节点，也不根据算子名称推断恢复策略。

## 有副作用算子

恢复协议不会重试失败算子。算子在抛异常前已经产生的外部副作用无法由 DAG 引擎回滚。
`sideEffectFree=false` 的算子仍可使用特征 `dft` 让本次 DAG 继续，但扩展作者必须自行保证
异常前副作用的业务可接受性。Native 不支持逐行恢复时直接选择 Single Adapter，而不是先
执行 Native、失败后重算，从而避免重复副作用。

## 缓存与观测

- `EvaluationFailure` 及包含失败元素的结果不得写入运行时缓存；
- 特征边界成功替换后，默认值作为普通值供下游计算；
- `RuntimeNodeState` 增加求值失败数量和特征 fallback 数量，成功恢复的请求不标记为整体失败；
- 已有 `fallbackUsed` 在至少一次替换时为 true；
- 核心链路不新增运行时日志；
- 公共观测快照不包含原始特征值、完整异常消息或 `Throwable`，保持现有隐私约束；
- 未配置 `dft` 时，`FEATURE_OUTPUT` 抛出内部特征求值异常，保留原始 cause、特征名和位置；
- 公共 API 将该异常映射为 `FeatureGenerationException`，补充 feature、row/group/candidate。

## 公共行为示例

特征：

```json
{
  "name": "score_result",
  "type": "INT",
  "definition_type": "DERIVED",
  "expression": "add(to_int(score), 10)",
  "dft": -1
}
```

Offline Batch 输入 `[12.8, 1.0E20, 3.6]` 时输出 `[22, -1, 13]`。第二行只执行到
`to_int`，随后传播失败并在 `score_result` 边界使用 `-1`；第一、三行和值顺序不受影响。

若两个特征共享同一个失败算子节点但分别配置 `dft: -1` 和 `dft: 999`，两个输出分别得到
`-1` 和 `999`。若其中一个没有非空 `dft`，执行到该特征边界时本次请求仍失败并保留原始
cause。

## 测试设计

所有新增测试使用独立 JUnit 4 `*Test.java`，不修改冻结的 `DagEngineSelfTest.java`。

### 核心传播

- Single 标准算子失败后使用特征 `dft`；
- 自定义条件失败算子证明运行时没有按标准算子名称特判；
- 嵌套表达式失败时后续算子未被调用，整个特征使用 `dft`；
- 带 `dft` 的中间特征解析失败后，下游使用默认值正常计算；
- 共享 producer 对不同特征分别应用不同 `dft`；
- 多个失败输入按参数顺序传播第一个 cause；
- 没有非空 `dft` 时保留 feature 和原始 cause；
- 正常 `null`/空容器与失败值的既有默认语义不回归。

### Batch 域

- Offline Batch 只替换失败行，后续行仍实际执行；
- Online 单请求只替换失败 candidate；
- Online Grouped Batch 正确保留 group execution ID 和组内 candidate index；
- 请求级失败只影响对应 group；
- 输出行数、组边界、候选顺序和正常值保持不变；
- 一个求值单元失败不阻止无关 DAG 分支计算。

### Kernel 和融合路由

- Scalar Adapter 收集逐行失败并继续；
- 支持逐行恢复的 Native Batch 仍按物理计划走 Native；
- 不支持新能力的扩展 Native 在 DAG 中计划为 Scalar Adapter；
- 直接 Registry Batch 调用仍按最小失败行号抛错；
- 非法失败下标、重复行、错误结果长度等协议错误 fail-fast；
- `SequenceKeyCountExecutor` 的融合路径与未融合路径逐行等价；
- 不支持恢复的专用执行器在需要默认恢复的路径上不融合。

### 异常边界和观测

- 未注册算子、参数数量、缺失 source、输入解码和计划不变量错误不使用衍生 `dft`；
- 自定义算子抛 `AssertionError` 时不捕获；
- 有副作用的失败算子只调用一次；
- 失败结果不进入缓存和公共编码器；
- `fallbackUsed`、求值失败数和 fallback 数正确；
- 无 `dft` 的异常保留 Single/row/group/candidate 位置。

### 验证命令

实施阶段至少运行：

```bash
mvn -Dtest=DerivedFeatureOperatorFallbackTest,OperatorBatchFailureRecoveryTest test
./scripts/run-self-test.sh
mvn clean package
```

`run-self-test.sh` 必须同时通过 `java -ea` 存量自测和全部 JUnit 4 测试。

## 文档更新

实现时同步更新：

- `README.md`：说明衍生 `dft` 可恢复算子求值异常；
- `docs/guides/operator-usage-guide.md`：说明 Single/Batch、嵌套表达式和无默认值行为；
- `docs/architecture/operator-single-batch-execution.md`：记录逐行失败结果和扩展 Native 能力；
- 必要的运行时架构文档：记录失败传播、融合兼容和观测字段。

## 实施边界

本次实现只改变算子求值异常到特征默认值的运行期数据流，不修改逻辑推断、节点 canonical
签名、特征声明一致性或公共配置格式。新增协议保持每个标准算子独立文件和
`InitialBusinessOperators` 显式注册清单不变，不新增标准算子，也不修改冻结的存量自测。
