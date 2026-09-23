# 第二轮 Batch 执行容器优化

## 实现

基于第一轮输出构建优化提交 `0c1723e4620e586db0cc3609f5a198dd0d7db788`，在独立工作区实现：

1. `DagRuntime.evaluateBatch` 仍检查全部输入行的失败状态，但全健康时使用连续行布局，不再生成和复制 `List<Integer>`。首次发现上游失败时才创建健康行投影及结果合并列表，并补齐此前扫描过的健康行。
2. 没有上游失败、Kernel 也没有失败时，直接只读复用内置 `ListBatchColumn.values()`。任意扩展 `BatchColumn` 仍立即复制，避免扩展算子保留可变列后污染输出。发生 Kernel 失败时保留原始行号及 group/candidate 定位、默认值处理和错误传播。
3. `DIRECT` 参数直接使用输入列，省掉序列适配包装；`MATERIALIZE` 保留适配，但仅在实际遇到 `OperatorSequence` 时创建物化 Map。同组同视图、跨参数复用；不同组或不同调用隔离。
4. `BatchOperatorResultBuilder` 在首次失败时创建 Map。`BatchOperatorResult` 对空失败集合使用共享不可变空 Map；非空集合继续校验和防御复制。无效 null 异常入参在添加占位行前拒绝。

决策仅依据批次失败状态、计划声明的序列能力和不可变结果列类型，不按业务算子名称特判。没有改变 Single 适配器的逐行参数 List 或增加原生 Batch 算子。

## 回归验证

新增独立 JUnit 4 `BatchFastPathRuntimeTest`（5 项）和 `BatchResultConstructionTest`（2 项）：

- 四种 Batch 域的行号/组号，包含空组和 300 行。
- 首行/中间/末尾的上游失败、连续布局上的 Kernel 失败、投影后的 Kernel 新失败、全失败和零候选。
- 自定义可变结果列的快照隔离、只读结果、null 成功值、失败 Map 输入隔离和非法行号。
- `DIRECT` 的视图身份；`MATERIALIZE` 的逻辑切片、跨参数与组内复用、跨组及跨调用隔离。

新增测试与现有 `SequenceViewRuntimeTest`、`OperatorBatchFailureFallbackRuntimeTest`、`RuntimeFailureFeatureAssociationTest`、`OutputConstructionTest` 定向运行共 23 项，均通过。

独立工作区全量 `mvn -q test`：251 项，8 项失败，0 error。用修改前的固定类文件运行相同存量 JUnit：244 项，同样 8 项失败，分别位于 `HwdspClick365dAllOperatorsTest`（2）、`HwdspClick365dBusinessCasesTest`（2）、`HwdspClick365dFullRegistryTest`（3）、`TransformTestExtendedOperatorsTest`（1）。新增 7 项全部通过。

显式执行 `scripts/run-self-test.sh`：在 `ModelFeatureSetInitialOperatorsSelfTest` 的 `adjusted_scores[0]: expected=1.0, actual=-1.0` 断言失败；修改前快照也在相同断言失败。未改冻结自测或业务期望值。

这里以第一轮 PR 提交为基线，没有带入原 `E:\feature` 工作区尚未提交的数值转换修复、业务测试和样本改动。因此该分支的测试总数和已知失败与原工作区此前报告不同；不能把原工作区 268 项通过当作本分支全量通过。

## 随 PR 补充：修复 `calc_delta_seq` 存量期望值

`3d02e1a`（PR #9）把 `calc_delta_seq` 默认方向改为 `BASE_MINUS_ELEMENT` 并同步了本文件断言；随后 PR #8（`76dc68e`）在并行分支上把断言改写为 padding 风格但保留了旧方向期望值，合并时测试文件取了 PR #8 一侧，方向修复被覆盖，`adjusted_scores` 断言自此在 main 上失败。本 PR 附带独立提交把两处期望值恢复为 `base - element` 语义（与 `docs/architecture/calc-delta-seq.md` 和 `CalculateDeltaSequenceConfigurationTest` 一致）。修复后断言环节推进到冻结自测 `DagEngineSelfTest` 的算子数断言（期望 20、实际 24）失败，该失败同样为 main 存量（`find_indices_any`、`append`、`join`、`intersection` 合入时未同步冻结清单），受冻结约束未改动，需维护者决策。

## 测量方法

同一台 Windows 11 / JDK 21.0.12 / 8 逻辑处理器，`-Xms4g -Xmx4g -XX:+UseG1GC`。复用原工作区的 `PersonScenePerformanceDemo.java` 合成场景：320 BASE + 320 DERIVED，160 条长度 730 的序列，一人一场 300 货，输入池 4，商品参数基数 300。每 JVM 主线程预热 1,000 次、4 工作线程各正式 2,500 次，共 10,000 次。

before 类文件在修改前编译并固定到 `target/batch-round2-before/classes`；after 固定到 `target/batch-round2-after/classes`。三轮交替对照不开 JFR。另以独立 JVM 采 before/after JFR，工作线程火焰图不包含主线程预热。分配使用 `ThreadMXBean.getThreadAllocatedBytes`，每版本预热 1,000 次、4 线程共测 2,000 次；计数包含 generate 和轻量结果消费，排除预构造输入和预热，仅覆盖工作线程。长测临时保持电脑唤醒，finally 释放。

本地原始文件位于本工作区 `target/batch-round2-benchmark/`。测试日志在 `target/surefire-reports/`；修改前存量 JUnit 与自测复核日志在 `target/batch-round2-before/`。这些是进程内闭环测试，不包含外部数据获取、网络与排队。

## 修改前热点复核

第一轮版本的新 JFR：10,566 个工作线程执行样本，截断栈 0；DAG runtime 84.13%，输入解码 8.03%，输出编码/复制 7.85%。`DagRuntime.evaluateBatch` 连同子调用占 47.91%，自身栈顶占 10.00%；Single 适配器栈顶占 5.92%。采样确认第二轮仍有优化价值，比例不等同于单请求墙钟耗时。

## 工作线程分配结果

| 版本 | 正式请求 | bytes/request | MiB/request | checksum |
|---|---:|---:|---:|---:|
| 第一轮基线 | 2,000 | 39,388,693.82 | 37.56 | 95000 |
| 第二轮 | 2,000 | 36,879,989.82 | 35.17 | 95000 |

每请求减少 2,508,704 bytes（2.39 MiB），相对第一轮基线下降 6.37%。这是本轮固定场景下的线程分配计数，不是存活堆或峰值内存，也不能代表全部业务配置。

固定 `DagRuntime.class` 的 SHA-256：before `8D1DE3E7B85D6FB92F6E9889EFEA2D9FBFD1F3AE523A0C78116923ED3928BD6A`；after `580D02E6A6D82402725B69A8A548D53C26E73C96788BB6177F9CF20F0B172E6B`。

## 六轮无 JFR 计时结果

| 轮次 | 请求/秒 | P50 ms | P95 ms | P99 ms | max ms | GC 次数 / 累计 ms |
|---|---:|---:|---:|---:|---:|---:|
| before-1 | 178.44 | 21.46 | 29.86 | 34.94 | 50.50 | 155 / 1187 |
| after-1 | 186.49 | 20.50 | 28.65 | 34.16 | 109.15 | 144 / 1135 |
| before-2 | 168.34 | 22.89 | 31.40 | 36.75 | 78.34 | 154 / 1235 |
| after-2 | 181.15 | 21.08 | 29.31 | 34.12 | 43.62 | 144 / 1100 |
| before-3 | 127.38 | 30.59 | 45.71 | 55.65 | 157.35 | 154 / 1551 |
| after-3 | 171.56 | 22.53 | 33.40 | 42.76 | 69.30 | 144 / 1138 |

全部轮次各 10,000 个正式请求，checksum 均为 475000。各版本三轮指标中位数：

- QPS：168.34 → 181.15（+7.61%）。
- P95：31.40 → 29.31 ms（-6.66%）。
- P99：36.75 → 34.16 ms（-7.06%）。
- QPS 范围：before 127.38–178.44；after 171.56–186.49。

以上是各轮统计量的中位数，不是合并全部请求后的分位数。before-3 明显变慢，after-3 也慢于另外两轮；after-1 的最大延迟反而高于 before-1。保留所有轮次，未确定环境波动的原因，不能把第三组相邻运行的全部差异归因于代码。测得分配下降且整体吞吐/分位数改善，但收益幅度仍需稳定环境和真实数据回放确认。

## 复跑命令

先在对应源码状态编译，并把 `target/classes` 保存为独立 before/after 快照，再顺序执行以下命令；不要在两版本之间复用会被重新编译覆盖的 classpath。

```powershell
$dependencies = (Get-Content -Raw target/demo-classpath.txt).Trim()
java -Xms4g -Xmx4g -XX:+UseG1GC `
  -cp "target/batch-round2-before/classes;$dependencies" `
  com.example.featuredag.demo.PersonScenePerformanceDemo `
  730 300 4 1000 2500 4 300 target/batch-round2-benchmark/new-before-1
```

切换 after 快照与新输出目录即可运行对应版本。每次检查退出码；原始参数、源文件哈希和执行顺序保存在 `target/batch-round2-benchmark/manifest.json`。

## 修改后 JFR 复核

after 取得 16,859 个工作线程执行样本，截断栈 0。`evaluateBatch` 包含子调用的样本占比从 47.91% 到 45.32%，自身栈顶从 10.00% 到 8.46%。哈希操作仍占主要热点，Single 适配器栈顶仍为 6.24%。采样比例的变化与容器优化方向一致，但不能据此推算精确节省的墙钟时间。

必须保留采样轮的环境限制：before JFR 轮 QPS 181.03、P99 33.72 ms，after JFR 轮 QPS 118.47、P99 57.84 ms，两轮相隔整个测试过程、各只有一次，after 明显慢于六轮正式计时。原因未确定，不能据此宣称绝对 CPU 耗时下降；它也再次表明桌面机器波动较大。JFR 数据未混入正式三轮中位数。

独立图文件为 `target/batch-round2-benchmark/before-flamegraph.html` 和 `after-flamegraph.html`；由仓库已有 JFR/Python 工具生成，树权重验证通过。对应的 `profile-before/`、`profile-after/` 保存 JFR、热点统计、CPU/分配 folded 栈及 JSON。
