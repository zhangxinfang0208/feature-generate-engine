# 第三轮：公共 Batch 参数容器复用（2026-09-28）

## 修改内容与兼容性

基于第二轮继续修改，原始 Git 基线为 06fb4d3eb7c66aadbdc3f42b21422bfe5c562444。
本轮优化点在公共 SingleLoopBatchOperatorKernel，仍调用 Single Kernel，不新增 Native Batch 或按算子名分支。

- 新增显式 BorrowedArgumentsKernel 协议：列表只在同步 evaluate 调用期间有效，不得修改、保存、返回，也不得让 iterator/subList/闭包等派生引用逃逸。需要保留时须复制；元素本身的生命周期不变。
- 每个 Batch 调用创建一套数组及只读列表包装，每行覆盖元素，避免每行新建参数 ArrayList 和底层数组。按列顺序读取一次，列读取错误继续直接传播，Kernel RuntimeException 仍按行恢复。
- 容器是方法局部变量，没有 Kernel 实例状态或 ThreadLocal，并发、重入和后续请求互不覆盖。
- 经调用链审计，仅 final 类 AddOperator、MulOperator、SliceByIndicesOperator、GetSequenceLengthOperator 显式 opt-in。Add/Mul 的 evaluateElementWise 同步求值，不持有传入容器；Slice/Length 只读取元素。
- AbstractBuiltinOperator 不声明该协议；未 opt-in 的扩展算子继续逐行收到独立可变列表，保留原有持有、修改和返回参数列表的行为。
- 本轮不修改算子算法、缓存策略或物理路由。Java 8 编译检查覆盖新协议和四个修改的内置算子。

累计提交同时包含前两轮的输出元数据复用、解码 Map 容量/查询优化、输出物化循环、切片少一次复制、
解码输入所有权交接、请求内批失败检查缓存及成功路径跳过逐行预检。详见前两轮报告。

## 正确性验证

新增 BorrowedArgumentsBatchTest 六项全部通过，覆盖扩展列表可变/保留/返回、借用只读与读取顺序、
行级失败、列错误、空批次/零参数、并发、重入、四个 opt-in 算子的 Single/Batch 等价，
以及溢出、null、序列广播、长度不一致、非法下标和 OperatorSequence。

本次重新执行全量 JUnit：第二轮 268 项/8 失败；第三轮 274 项/8 失败。
八项失败的完整异常与堆栈一致，没有新增失败，不代表全量测试通过：

- HwdspClick365dAllOperatorsTest：offlineBatchMatchesSingleAndCoversNoTargetSlot、generatedValuesExactlyMatchThePublishedResultSet。
- HwdspClick365dBusinessCasesTest：coversNoMatchAndMisalignedSequenceBoundaries、generatesExpectedPackageStrength。
- HwdspClick365dFullRegistryTest：verbatimPlatformConfigParsesBuildsAndGenerates、documentedSampleRowsMatchEngineOutputs、offlineBatchMatchesSingleAndHandlesNoTargetCategory。
- TransformTestExtendedOperatorsTest：batchMatchesSingleAndNoMatchRowKeepsLiteralBaseline。

scripts/run-self-test.sh 已执行，但环境缺少 Maven，返回 Maven is required。
使用本地 OpenJDK 21 javac --release 21 编译所有生产及测试源码，再以 JUnitCore 验证。
手工 java -ea 执行冻结 DagEngineSelfTest，仍失败于 Expected 20 operators, got 24，未修改冻结测试。
Jackson databind/annotations 2.14.0、core 2.14.1（不同于 POM 2.21.3），JUnit 4.13.2，
两版均使用相同依赖；标准 Maven 构建仍需在完整环境验证。

## 第二轮与第三轮对照

Linux / OpenJDK 21.0.12 / JVM 可见 8 CPU / 3 GiB 堆 / Generational ZGC。
这不是用户目标 11C45G/22 GiB 堆环境，数值仅用于本环境版本对照。

320 BASE + 320 DERIVED、160 条长度 384 的序列、300 候选；
8 个预构造请求轮换，单调用线程闭环。只测 generate，Bridge 和 OnlineGenerateRequest 构造不计入。
每个 JVM 预热 15 秒、测量 30 秒，JFR 与 observer 关闭。
顺序为 round2-1 → round3-1 → round2-2 → round3-2 → round2-3 → round3-3。
六轮均在测量前后通过全候选输出 oracle；配置 SHA256 一致，CSV 数量及平均时延核对通过。

以下为三次独立 JVM 指标中位数，不是合并请求后计算的分位数：

| 指标 | 第二轮（本次重跑） | 第三轮 | 变化 |
|---|---:|---:|---:|
| QPS | 57.912 | 61.641 | +6.44% |
| P50 ms | 16.650 | 15.742 | -5.46% |
| P95 ms | 20.526 | 19.489 | -5.05% |
| P99 ms | 24.819 | 22.915 | -7.67% |
| 分配 MiB/请求 | 42.955 | 34.383 | -19.96% |

| 轮次 | QPS | P50 ms | P95 ms | P99 ms | 分配 MiB/请求 |
|---|---:|---:|---:|---:|---:|
| round2-1 | 58.883 | 16.397 | 19.927 | 24.819 | 42.697 |
| round3-1 | 63.212 | 15.333 | 18.745 | 21.882 | 34.131 |
| round2-2 | 57.912 | 16.650 | 21.228 | 25.360 | 42.972 |
| round3-2 | 60.089 | 15.965 | 20.344 | 25.009 | 34.934 |
| round2-3 | 57.908 | 16.741 | 20.526 | 24.180 | 42.955 |
| round3-3 | 61.641 | 15.742 | 19.489 | 22.915 | 34.383 |

三组均有 QPS、P95/P99 和分配改善；第二组 P99 改善较小。结果支持保留本轮优化，
不保证线上可获得同等收益，也未将前三轮不同时间段的提升相乘作为累计收益。
原始六轮指标、请求时延 CSV 见 [performance-20260928-round3](performance-20260928-round3/)。

复现应分别编译第二轮与第三轮源码，使用相同依赖和如下基准入口：

~~~bash
java -Xms3g -Xmx3g -XX:+UseZGC -XX:+ZGenerational -XX:FlightRecorderOptions=stackdepth=128 -cp "$BENCH_CP" com.example.featuredag.performance.LargeOnlineBenchmark "$BENCH_OUTPUT" 15 30 384 300 false false
~~~

BENCH_CP 包含对应版本生产 classes、测试 classes、src/main/resources 和 Jackson 依赖；
BENCH_OUTPUT 每次使用不同目录。精确命令保存在下载包 results/comparison/*/command.json。

## 新火焰图

计时对照完成后，单独用第三轮版本采样 15 秒预热 + 30 秒测量；
不将采样轮的时延和 QPS 混入上表。共保留 Java CPU 样本 1317 个，
分配样本 4381 个。CPU 叶热点如下：

| 方法 | 叶样本占比 |
|---|---:|
| java.util.HashMap.getNode | 13.97% |
| java.util.HashMap.putVal | 13.74% |
| com.example.featuredag.operator.SingleLoopBatchOperatorKernel.evaluateBorrowedArguments | 10.48% |
| com.example.featuredag.operator.builtin.SliceByIndicesOperator.slice | 7.52% |
| com.example.featuredag.runtime.ExternalValueMaterializer.materializeRaw | 7.37% |
| java.util.HashMap.put | 5.01% |
| com.example.featuredag.api.FeatureOutputEncoder.encodeValue | 4.78% |
| com.example.featuredag.operator.builtin.OperatorSupport.evaluateElementWise | 4.63% |

新借用参数循环仍有 CPU 开销：逐行读取列、调用 Single Kernel、构建结果；
优化减少参数容器分配，并未消除这些工作。Map 读写、输出物化和切片仍是后续候选热点。

分配类别中 Object[] 33.11%、LinkedHashMap.Entry 24.95%、HashMap.Node[] 12.85%、ArrayList 4.75%。
这是抽样权重占比，不是每请求精确分配或存活内存；准确的总分配对照以上述计时轮为准。
CPU 火焰图仅展示请求 Java 采样，不包含完整 native/GC CPU 或等待时间；
不能用独立采样前后的百分比差作为绝对耗时改善。

本次采样无 ZAllocationStall，181 次 GC pause 总计 4.609 ms，最大 0.246 ms。
交互图见 [flamegraphs.html](performance-20260928-round3/flamegraphs.html)，
采样明细见 [profile-summary.json](performance-20260928-round3/profile-summary.json)。

## 交付范围

远端 PR 包含累计三轮全部生产代码修改、三份新增 JUnit 测试、契约文档、三轮报告及本轮计时/火焰图证据。
下载包额外提供累计补丁、第三轮增量补丁、原始 JFR 和测试/GC 日志。
未调整既有业务测试期望，未提交构建 classes、JDK 或第三方依赖。
