# `find_indices_any` Native Batch 性能验证报告

## 结论

`find_indices_any` 适合在“同一在线 group 的候选行共享长源序列、每行 targets 不同”的场景使用
Native Batch。Top5 查询下，源序列长度 1000 和 3000 时相对 `SCALAR_ADAPTER` 分别达到
1.63x 和 3.28x；独立源序列不建索引，整体与 Adapter 基本持平。

Native Kernel 不是无条件建索引：批首没有观察到同 group 至少 4 个连续行共享源对象时直接委托
`SingleLoopBatchOperatorKernel`；源序列短于 512 时走线性扫描；进入索引路径后，如果目标离散值或
实际命中位置达到四分之一，则切回线性扫描。该选择只发生在算子内部，不在 planning/runtime 按算子名
增加分支。

## 环境与方法

- 日期：2026-09-03
- 系统：Windows 10.0.26200，Intel Core i7-1165G7
- Java：OpenJDK Temurin 21.0.12
- 工作负载：4 个 group × 每组 500 个候选，共 2000 行；值域 64；每行 5 个目标值
- 场景：`shared` 为组内 500 行共享同一源序列对象；`independent` 为每行独立源序列对象
- 路径：强制 `SCALAR_ADAPTER` 与注册的 `NATIVE`；计时包含 Kernel 求值和结果构造，不包含 DAG
  规划、解码、调度和编码
- 统计：每个 JVM 先预热 5 轮、测量 9 轮取中位数；独立启动 3 个 JVM，再取 median-of-medians
- 正确性：每个场景计时前逐行比较两条路径结果，消费结果 checksum，避免无效测量

复现命令：

```powershell
1..3 | ForEach-Object {
    ./scripts/run-find-indices-any-batch-performance.ps1
}
```

## Top5 主结果

`speedup = scalar median / native median`，大于 1 表示 Native 更快。

| 场景 | 序列长度 | Scalar 中位数 (ms) | Native 中位数 (ms) | Speedup |
| --- | ---: | ---: | ---: | ---: |
| shared | 50 | 1.573 | 1.359 | 1.16x |
| independent | 50 | 1.130 | 1.260 | 0.90x |
| shared | 200 | 2.583 | 2.344 | 1.10x |
| independent | 200 | 2.731 | 2.716 | 1.01x |
| shared | 1000 | 10.359 | 6.350 | 1.63x |
| independent | 1000 | 10.769 | 10.419 | 1.03x |
| shared | 3000 | 28.763 | 8.765 | 3.28x |
| independent | 3000 | 28.507 | 27.987 | 1.02x |

50 元素独立场景的 0.13 ms 差值约为每行 65 ns；该场景实际委托同一个标量适配协议，结果容易受
JIT、GC 和测量顺序影响，不代表额外的序列算法复杂度。长独立序列保持在 0.98x～1.10x 波动范围。

## 目标数量敏感性

对共享长序列额外验证目标数量：

| 目标数 | 序列长度 | Scalar 中位数 (ms) | Native 中位数 (ms) | Speedup | 选择路径 |
| ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 1000 | 10.046 | 2.133 | 4.71x | 稀疏索引合并 |
| 1 | 3000 | 26.769 | 1.563 | 17.13x | 稀疏索引合并 |
| 20 | 1000 | 16.989 | 17.888 | 0.95x | 密集命中线性扫描 |
| 20 | 3000 | 40.344 | 39.686 | 1.02x | 密集命中线性扫描 |

目标数 20 已覆盖值域的 31.25%，因此不再合并并排序大量位置；结果回到与 Adapter 接近的水平。
结论是 Native 的主要收益来自“长共享源序列 × 稀疏多目标查询”，而不是 Batch 接口本身。

## 正确性与路由验证

专项 JUnit 4 覆盖：

- 标准注册和恢复路径均选择 `NATIVE`；
- Native 与 Single 的源顺序、重复目标集合语义和空目标结果一致；
- 同一源对象每个 group 只扫描一次，不跨 group 共享索引；
- 非法 targets 只标记对应失败行，后续健康行继续执行；
- 公共 API 的嵌套 `find_indices_any -> slice_by_indices -> group_count_concat` 结果不变。

验证命令：

```bash
mvn test
./scripts/run-self-test.sh
```

本次实际执行结果：

- `FindIndicesAnyOperatorTest`、`NativeBatchOperatorFailureRecoveryTest`、
  `SequenceViewOperatorSupportTest`、`HwdspClick365dApplicableOperatorsTest`：通过；
- `mvn test`：210 个 JUnit 中 202 个通过，8 个既有业务 fixture 断言失败，分布在
  `HwdspClick365dAllOperatorsTest`、`HwdspClick365dBusinessCasesTest`、
  `HwdspClick365dFullRegistryTest`、`TransformTestExtendedOperatorsTest`；这些失败链路未使用
  `find_indices_any`，临时强制该算子恢复 `SCALAR_ADAPTER` 后仍可复现；
- `run-self-test.sh`：在进入本次算子路由检查前，既有
  `ModelFeatureSetInitialOperatorsSelfTest.adjusted_scores` 断言失败；按仓库约束未修改冻结自测。
