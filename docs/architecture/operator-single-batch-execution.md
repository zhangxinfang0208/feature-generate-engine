# 算子 Single/Batch 双执行契约

## 1. 目标

逻辑 DAG 的算子仍表达单值语义；运行时批维度位于 `ValueShape` 之外。每个算子必须具备
`SingleOperatorKernel`，可以选择实现原生 `BatchOperatorKernel`。未提供原生 Batch 的算子由
`SingleLoopBatchOperatorKernel` 逐行适配，因此新增算子无需复制两套实现。

物理节点融合仍由 `PhysicalRewriteRule` 在初始化阶段完成。请求执行阶段只调用物理计划已经声明的
Single、Batch 或 SPECIALIZED 执行器，不重新匹配规则、不修改 DAG（C9/C10）。

## 2. 执行契约

Batch Kernel 必须满足逐行等价：

```text
batch(arguments)[i] == single(arguments[i])
```

同时满足：

- 输出行数等于输入行数，零行 Batch 返回零行结果；
- 不改变行顺序，不把运行时 Batch 维度混入逻辑 `ValueShape`；
- 可恢复调用通过 `BatchOperatorResult.rowFailures()` 按行报告 Kernel `RuntimeException`；
- 直接 fail-fast 调用把最小失败行转换为携带 Batch 内行号的 `BatchOperatorEvaluationException`；
- Kernel 实例无请求状态并可被并发复用。

`BatchLayout` 描述 `OFFLINE_ROW`、`ONLINE_REQUEST`、`ONLINE_CANDIDATE` 三种批域，在线候选行
可以映射回 group 和组内 candidate 下标。输入通过只读 `BatchColumn` 暴露；标量广播以及
request-to-candidate 广播由运行时虚拟列完成，不复制展开后的值。

## 3. 规划期选择

普通算子物理节点记录：

```text
singleKernelId
batchKernelId
batchKernelKind = NATIVE | SCALAR_ADAPTER
invocationPolicy = OperatorInvocationPolicy.SINGLE_OR_BATCH_BY_INPUT_DOMAIN
sequenceViewInputMode = DIRECT | MATERIALIZE
```

规划器只为未被 Rewrite 消费的逻辑算子生成上述配置。命中融合规则时仍生成
`ExecutorType.SPECIALIZED` 节点，并记录全部 consumed logical node IDs。

`invocationPolicy` 使用物理层枚举，`DagRuntime` 必须读取后执行对应分派，不能只把它作为计划打印配置。
运行时无批值输入时调用 Single Kernel；存在 `OfflineBatchValue`、`RequestBatchValue`、
`CandidateVectorValue` 或 `CandidateBatchValue` 时调用计划声明的 Batch Kernel。这是对输入载体的
固定分派，不是运行时物理改写。

`NodePlanningMetadata.failureRecoveryRequired` 表示节点可到达至少一个具有非空 `dft` 的衍生特征
边界。恢复路径选择 Batch Kernel 时，只有实现 `RecoverableBatchOperatorKernel` 的 Native Kernel
仍使用 `NATIVE`；旧扩展 Native Kernel 自动改用 `SCALAR_ADAPTER`，其普通 fail-fast 路径不受影响。
这使旧扩展无需立即修改，也避免无法定位行号的 Native 异常扩大成整批失败。

`sequenceViewInputMode` 由 `OperatorDefinition.supportsSequenceView()` 推导并固化。`DIRECT` 表示
Single 与 Native Batch Kernel 可以直接消费 operator 层的 `OperatorSequence`；`MATERIALIZE`
表示运行时在 Kernel 边界按视图逻辑范围转换为只读 `List`。Batch 的适配发生在 Native Kernel 与
`SingleLoopBatchOperatorKernel` 的共同入口之前，同一 group 内重复的具体视图按对象身份只物化一次。
完整契约见 `docs/architecture/sequence-view-operator-support.md`。

## 4. 缓存与批内复用

通用候选批去重路径（CANDIDATE_KEY）已移除：批内重复计算由原生 Batch 的 identity 键复用消除
（`find_indices`/`count_distinct`/`zip_concat`/`calc_delta_seq` 在 Kernel 内部按
`(group, sequence, 参数)` 身份键缓存；其中 `calc_delta_seq` 的参数键覆盖 `base`、`direction`
和 `divisor`）；未提供原生 Batch 的算子由 `SCALAR_ADAPTER` 逐行计算。
缓存资格仍以 deterministic 且 sideEffectFree 为准（C8 元数据）。

专用序列索引缓存仍由注册式 `PhysicalExecutor` 管理。跨 group 不得共享请求级序列、索引或
计数缓存。

## 5. 与融合的关系

```text
初始化：Logical DAG → Rewrite 选择 → PhysicalPlan
调用：  PhysicalPlan → Single/Batch/SPECIALIZED Kernel
```

逐行 Native Batch 使用同一个 `RowEvaluator` 同时生成 Single 与 Batch 路径，Batch 侧通过可移动
行视图访问列值，不创建逐行参数 List。它减少逐行调度和参数对象，但不自动减少逐行业务计算；
批内重复计算由 Native Batch 的身份键复用消除。Rewrite 仍负责跨节点消除中间物化和改变算法复杂度，例如
把“按 key 过滤序列后 count”转换为“一次建索引后按 key 查询”。两者是互补能力。

Single 恢复调用使用 `OperatorEvaluationResult` 表示成功值或 Kernel `RuntimeException`。Batch 恢复调用
使用 `BatchOperatorResultBuilder` 保持结果行和失败行一一对齐；原生 Batch 若要在恢复路径继续走
`NATIVE`，必须实现 `RecoverableBatchOperatorKernel` 并返回每个失败行。运行时先排除已经从上游继承失败
的行，只把健康行投影给 Kernel，再按原始 row/group/candidate 位置散射结果。失败单元不会重试，失败
对象不会进入缓存。

融合改写还必须用 `PhysicalRewrite.failureRecoverySupported` 声明专用执行器是否具有等价的隔离能力。
恢复必需路径不会选择未声明能力的 Rewrite；声明支持后，专用执行器必须逐 group/candidate 隔离失败，
并保持融合前后的影响范围一致。

## 6. 运行时观测

`RuntimeNodeState` 与 `ObservationDetailLevel.NODE` 下的 `NodeExecutionSnapshot` 记录
`OperatorInvocationKind`：

```text
SINGLE
BATCH_NATIVE
BATCH_SCALAR_ADAPTER
SPECIALIZED
```

Batch 路径同时记录 `batchDomain` 和 `batchRowCount`。`batchRowCount` 表示该节点在原始运行域中的
求值单元数；恢复路径即使只把健康投影行提交给 Kernel，也保持原始 row/group/candidate 规模。
Single、SPECIALIZED 和非算子节点不携带 Batch 域，行数为零。
失败节点保留失败前已经确定的调用路径，便于判断问题发生在 Single、Native Batch、Adapter 还是融合执行器。
成功恢复的节点通过 `operatorFailureCount` 和 `fallbackCount` 记录 Kernel 失败数与实际默认值替换数；
快照不包含结果值、异常消息或 `Throwable`。

## 7. 测试要求

- 首期 8 个算子中 `find_indices`、`count_distinct`、`zip_concat`、`calc_delta_seq` 提供原生 Batch（批内按 identity 键复用）；`discrete`、`log_base`、`slice_by_indices`、`get_seq_length` 不提供原生 Batch（复用收益不足以覆盖批开销，实测劣化约 0.1x~0.3x），由 `SCALAR_ADAPTER` 逐行适配；
- 每个标准 Native Batch 使用相同输入逐行对比 Single 结果；
- `SCALAR_ADAPTER` 保持调用次数、顺序和异常语义；
- `DIRECT` 保留具体 `OperatorSequence`，`MATERIALIZE` 只包含视图 selection 内的元素；
- Single、Native Batch 与 `SCALAR_ADAPTER` 的视图输入逐行等价；
- 零行、单行、多行 Batch；
- request-to-candidate 广播及多 group 隔离；
- Batch 错误映射到 offline row 或 online group/candidate；
- 恢复路径只调用健康行，失败行短路且后续健康行继续执行；
- 旧 Native 扩展在恢复路径改用 Adapter，带恢复标记的 Native 保持原生路由；
- 原生 Batch 按 identity 键复用与 Single 逐行结果一致；
- 命中 Rewrite 时仍执行 SPECIALIZED 物理节点，未命中时才走普通 Batch Kernel；
- 节点诊断准确区分 Single、Native Batch、Adapter Batch 和 SPECIALIZED，并记录批实际行数。
