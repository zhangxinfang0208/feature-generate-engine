# 公共代码热点优化与复测（2026-09-28）

## 代码基线及范围

基于 `06fb4d3eb7c66aadbdc3f42b21422bfe5c562444`，修改 5 个生产文件，新增 4 项独立 JUnit 4 测试。

1. `FeatureDagEngine` / `FeatureOutputEncoder`：每个输出特征在候选或批行循环外解析一次 OutputSpec，单行/共享编码也复用已解析配置；保持异常消息、形状和默认值语义。300×320 输出下，从每请求约 96,000 次配置查询降到约 320 次。
2. `FeatureInputDecoder`：正常非空输入只执行 get；仅 null 值时用 containsKey 区分缺失和显式 null。LinkedHashMap 按源字段与输入大小的较小值预分配，保留遍历顺序。
3. `ExternalValueMaterializer`：列表物化统一使用预分配循环，减少 Stream 临时对象；保留嵌套列表/Map 递归快照、null 和不可变结果。
4. `SliceByIndicesOperator`：内部独占的结果列表直接不可变包装，移除再次复制；保留重复下标、顺序和全部边界验证。

本轮主要修改公共编解码路径，仅第 4 项属于具体算子。ExecutionContext 容器所有权交接、CandidateVectorValue 可信构造、Batch 参数复用与失败元数据未在本轮引入，仍需单独设计与验证。

## 同环境对照

Linux、OpenJDK 21.0.12、JVM 可见 8 个处理器、-Xms3g -Xmx3g、分代 ZGC。没有 Maven，使用 javac --release 21 重新编译完整生产源码及测试；Jackson databind/annotations 2.14.0、core 2.14.1，JUnit 4.13.2。Jackson 与 POM 的 2.21.3 不一致，两版本使用同一套依赖；初始化和配置解析不计入测量。

负载：320 BASE、320 DERIVED、160 条长度 384 的序列、300 候选、8 个预构造请求轮换、单线程闭环、观测关闭。输入构造和业务 Bridge 不在测量范围内。不能据此推导 11C45G/22g 堆或线上并发容量。

执行顺序：before-1 → after-1 → before-2 → after-2 → before-3 → after-3；每次独立 JVM，预热 15 秒、测量 30 秒，计时轮不开 JFR。前后均执行独立 Java oracle 校验全部候选输出。六轮 features.json SHA-256 一致，逐请求 CSV 条数和均值已核对。每轮仅千余请求，P99 仍需更长时间及业务负载验证。

以下是每轮统计量的中位数，并非合并请求后重新计算的分位数：

| 指标 | before | after | 变化 |
|---|---:|---:|---:|
| QPS | 47.666 | 54.147 | +13.60% |
| P50 ms | 20.140 | 17.853 | -11.36% |
| P95 ms | 24.938 | 22.693 | -9.00% |
| P99 ms | 30.807 | 29.411 | -4.53% |
| 分配 MiB/请求 | 48.698 | 44.579 | -8.46% |

### 原始轮次

| 轮次 | QPS | P50 ms | P95 ms | P99 ms | 分配 MiB/请求 |
|---|---:|---:|---:|---:|---:|
| before-1 | 47.333 | 20.140 | 26.646 | 32.992 | 47.050 |
| after-1 | 54.339 | 17.853 | 21.741 | 27.160 | 44.579 |
| before-2 | 47.666 | 20.412 | 24.938 | 30.807 | 48.698 |
| after-2 | 52.607 | 18.393 | 23.226 | 30.240 | 44.585 |
| before-3 | 49.009 | 19.725 | 24.347 | 30.269 | 48.698 |
| after-3 | 54.147 | 17.799 | 22.693 | 29.411 | 44.579 |

数值是合并优化收益，没有逐项消融，不能拆分归因。分配量来自调用线程分配计数，不是存活堆。

## 新火焰图

优化后单独运行 15 秒预热 + 30 秒 JFR 采样，保留 1,641 个请求 CPU 样本。

- `MapN.probe` 叶样本：之前 62/1710，之后 1/1641。
- `UnmodifiableCollection.stream` 叶样本：之前 77/1710，之后 0/1641。
- 优化后剩余 CPU 叶热点：SingleLoopBatchOperatorKernel.evaluateBatch 12.25%、HashMap.putVal 10.85%、HashMap.getNode 10.42%、DagRuntime.evaluateBatch 10.36%、ExternalValueMaterializer.materializeRaw 10.05%、SliceByIndicesOperator.slice 9.87%。
- 样本占比不是绝对耗时，热点占比上升不代表该路径变慢。CPU 图仅覆盖请求 Java 执行栈，不含完整 native/GC CPU 或等待。
- 采样轮出现 4 次 ZAllocationStall，JFR 总时长约 162.24 ms，最大约 87.47 ms；该轮 QPS 47.96、P99 46.70 ms，仅用于定位热点，不混入无采样收益表。六轮无采样 GC 日志未记录 Allocation Stall (main)。

## 正确性与限制

- 相关测试 29 项通过，新增 4 项覆盖缺失/null 区分、解码隔离、输出截断/补齐、嵌套可变对象快照、LinkedList 输入、切片重复下标和边界。
- 全量 JUnit：before 259 项/8 失败，after 263 项/8 失败；失败方法集合一致，均为已有 365d 业务预期差异。不能标为全量回归通过。
- 显式尝试 scripts/run-self-test.sh：环境无 Maven，脚本报告 Maven is required。随后直接以 java -ea 执行冻结 DagEngineSelfTest，前后均在 Expected 20 operators, got 24 断言失败。
- 修改的 SliceByIndicesOperator 单独以 --release 8 编译通过。
- git diff --check、SVG XML 解析通过；没有修改冻结的 SelfTest。

## 使用修改包

ZIP 的 src/ 包含全部修改文件和新增测试，覆盖到 06fb4d3 基线对应路径即可；changes.patch 包含相同修改。建议在标准 JDK21/Maven 环境恢复 POM 依赖后执行 mvn test 及 scripts/run-self-test.sh，并单独处理上述既有失败。

results/ 包含六轮摘要、原始时延、GC 日志、前后测试日志及优化后的 JFR 和交互火焰图。使用仓库 LargeOnlineBenchmark 复现：参数为 outputDir 15 30 384 300 false false；独立采样轮将最后一个参数改为 true，再运行 JfrFlamegraphExporter 和 scripts/render-jfr-flames.py。
