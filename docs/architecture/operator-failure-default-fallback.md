# 算子失败使用衍生特征默认值

## 1. 行为概览

特征 DAG 内的算子 Kernel 抛出 `RuntimeException` 时，引擎把异常转换为仅在运行时内部流转的失败值，
并在所属衍生特征的 `FEATURE_OUTPUT` 边界统一处理：

```text
Kernel RuntimeException
  -> EvaluationFailure
  -> 下游对该求值单元短路
  -> FEATURE_OUTPUT
       -> 非空 dft：替换失败值并继续
       -> 无 dft：抛出 FeatureEvaluationException
```

“短路”不会终止进程。它只表示已经失败的 Single 值、Batch 行、请求组或候选不再执行其下游
算子；其他健康求值单元和无关 DAG 分支继续执行。失败到达非空 `dft` 边界后会变成普通默认值，
后续依赖该特征的算子按正常输入继续计算。若到达没有默认值的特征边界，本次生成请求失败，公共
`FeatureGenerationException` 保留 `featureName` 和原始 Kernel cause。

## 2. 恢复边界

| 场景 | 是否使用衍生 `dft` | 行为 |
| --- | --- | --- |
| `OperatorDefinition.evaluate` 抛 `RuntimeException` | 是 | 在所属特征边界恢复 |
| 可恢复 Native Batch 报告某行失败 | 是 | 只替换该行/候选 |
| 融合执行器声明并实现恢复能力 | 是 | 按其 group/candidate 边界隔离 |
| 特征没有非空 `dft` | 否 | 抛错并保留特征名、位置和原始 cause |
| JSON、表达式解析、未知算子、参数数量、推断或依赖环错误 | 否 | 初始化 fail-fast |
| RAW 缺失、类型转换或公共输入解码错误 | 否 | 输入边界 fail-fast；RAW 只使用自己的默认值 |
| 物理计划、Batch 行数/映射、缓存类型或输出编码错误 | 否 | 协议边界 fail-fast |
| 直接调用 `OperatorRegistry.evaluate/evaluateBatch` | 否 | 保持原有 fail-fast 契约 |
| 任意 `Error` | 否 | 原样传播，不捕获 |

关键区分是异常发生的位置。例如，声明为 BIGINT 的 RAW 输入在解码阶段超出 `Long` 范围，衍生
`dft` 不能恢复；声明为 DOUBLE 的 RAW 进入 `to_bigint` 后在 Kernel 内溢出，则可以由该衍生特征
的 `dft` 恢复。

## 3. 可避免的算子异常场景

该机制不按业务算子名判断，因此覆盖所有标准算子和通过公共扩展入口注册的算子。只要异常在
Kernel 求值内部表现为 `RuntimeException`，以下类别均可在配置非空 `dft` 后避免中断整个请求：

| 类别 | 典型场景 |
| --- | --- |
| 数值转换与算术 | `to_int`/`to_bigint` 超范围、非有限数或非法数值载体；`add/sub/mul` 定宽溢出；极值或算术输入类型错误 |
| 对数与分桶 | `log_base` 的 value/base/upbound 非法；`discrete` 边界或输入类型非法 |
| 序列访问与聚合 | 输入不是序列、索引越界或类型非法、事件对象不被支持、序列视图读取失败 |
| 拼接、命中与分组 | 序列长度不一致、配置对象或分隔符非法、事件字段缺失或类型错误、元素比较/哈希/字符串转换失败 |
| 差值序列 | base、divisor、direction 或配置非法，元素不是有限数，结果溢出有限 `Double` 范围 |
| 扩展算子 | 业务校验异常、第三方库异常、`IllegalStateException`、`NullPointerException`、`ClassCastException` 或自定义 `RuntimeException` |

各标准算子的逐项参数与边界清单见设计说明
[`operator-failure-default-fallback-design.md`](../superpowers/specs/2026-08-27-operator-failure-default-fallback-design.md)。
捕获所有 Kernel `RuntimeException` 也可能掩盖扩展实现缺陷，因此必须监控恢复计数并及时修复根因，
不能把 `dft` 当作输入校验或代码质量的替代品。

## 4. Single、嵌套和共享特征

- Single Kernel 失败产生 `FailedValueHandle`。同一算子有多个失败输入时按参数顺序传播第一个失败，
  下游 Kernel 不再调用。
- 嵌套表达式不会在中间任意节点注入默认值；整个所属特征在 `FEATURE_OUTPUT` 使用一次自己的 `dft`。
- 带 `dft` 的中间特征会在自身边界把失败解析为普通值，下游特征随后正常计算。
- canonical 共享 producer 只保存失败，不绑定消费者默认值。不同特征可对同一次失败分别使用不同
  `dft`；任一无 `dft` 的目标仍会在自己的边界失败。

## 5. Batch 隔离

运行时先找出从上游继承失败的求值单元，只把健康单元投影给 Batch Kernel，再将成功值和新失败按
原始位置散射。因此输入 `[正常, 异常, 正常]` 的输出保持 `[结果, dft, 结果]`，失败后的健康单元
仍会实际执行且不会重算。

隔离范围如下：

- Offline Batch：单个失败 row；
- Online Request Batch：单个失败 request group；
- Online Candidate：单个失败 candidate；
- Online Grouped Batch：保持 group execution ID、组边界和组内 candidate 顺序；
- 专用序列计数融合：索引构建失败影响该请求组，key 规范化失败只影响该 candidate，同一 key 的
  count 失败只影响映射到该 key 的候选。

Native Batch 通过 `BatchOperatorResult.rowFailures()` 报告局部失败。已经实现
`RecoverableBatchOperatorKernel` 的 Native 保持原生路由；旧扩展 Native 在恢复必需路径自动使用
逐行 `SCALAR_ADAPTER`。两种路径都不重试失败行。

## 6. 缓存、副作用和可观测性

失败值不会写入成功结果缓存，失败 Kernel 不重试。对于有副作用的扩展算子，引擎无法回滚异常前
已经发生的外部操作；新增算子应明确纯度，并优先保持无请求状态、无副作用。

`ObservationDetailLevel.NODE` 的 `NodeExecutionSnapshot` 提供：

- `operatorFailureCount`：该节点本次 Kernel 新产生的失败求值单元数，不重复统计上游继承失败；
- `fallbackCount`：该特征输出节点实际使用 `dft` 替换的求值单元数；
- `fallbackUsed`：是否至少发生一次默认值替换。

诊断快照只包含结构、计数、耗时和错误类型，不包含结果值、Kernel 异常消息或 `Throwable`。

## 7. 扩展要求与测试

- Single 扩展无需新接口，Kernel `RuntimeException` 自动进入恢复协议；直接 Registry 调用不恢复。
- 希望恢复路径继续使用 Native Batch 的扩展必须实现 `RecoverableBatchOperatorKernel`，返回完整、合法、
  按行对齐的失败映射；否则框架使用逐行 Adapter 保证隔离。
- 融合 Rewrite 必须显式声明 `failureRecoverySupported`；只有专用执行器已实现等价隔离后才可设为
  `true`，否则恢复必需路径不采用该融合。
- 测试必须覆盖 Single、Offline Batch、Online Candidate、Online Grouped Batch、嵌套表达式、共享
  producer、无 `dft`、Native/Adapter 路由、融合路径、`Error` 不捕获，以及公共异常和诊断计数。
