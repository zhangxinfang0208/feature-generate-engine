# 火焰图驱动的整数运算与输出编码优化

2026-09-24，在 `742b0e1` 上完成两处局部优化。同日重测的 8 GiB 分代 ZGC、单调用线程、320 base（160 条长度 384 的序列）、320 derived、500 货负载下，三轮中位 P50 降低 18.6%、P95 降低 18.1%、吞吐提高 23.2%，每请求分配从 114.294 MiB 降到 76.138 MiB（减少 33.4%）。这是单线程闭环服务时间，不代表线上并发容量。

## 改动及原因

1. `AddOperator`、`MulOperator`：当两侧均为 Byte/Short/Integer/Long 时，使用 `Math.addExact` / `Math.multiplyExact`，直接输出 Long。先前即使是小整数也会构造精确十进制操作数、结果以及有限性错误文本。快路径避免这些分配；溢出时回到原有 BigDecimal 计算，保持异常类型和精确结果文本。BigInteger、BigDecimal、浮点数及混合输入仍使用原有路径，保留十进制精度与载体语义。未新增 Native Batch；序列广播与 Batch 仍通过原有协议执行。
2. `FeatureOutputEncoder`：只在输出名不存在时拼接 `Unknown output feature` 提示。原来每请求的 500 × 320 个输出都会提前构造正常路径用不到的错误字符串。保持相同的 NullPointerException 类型与消息。

`OperatorSupport` 增加包内整型载体判定以供两个算子使用。核心规划、缓存、运行时路由和公共 API 不变。所有新增内置算子代码使用 Java 8 可用的语法和 API。

## 对照方法与结果

优化前为当天未修改的 `742b0e1`，优化后为本次工作区代码。均固定 `-Xms8g -Xmx8g -XX:+UseZGC -XX:+ZGenerational`，Java 21.0.12+8，Windows 10，12 逻辑处理器。8 个不可变请求轮换，NOOP 观察器。初始化不计入耗时；公共 API 的解码、DAG 执行和输出物化均计入。

前后各 3 个独立 JVM，每次预热 15 秒、测量 30 秒，均未开启 JFR。先完成优化前 3 次，再做整数路径单项探查，最后完成两项合并后的 3 次。表内每项为逐 JVM 指标中位数，不是合并请求后计算的 percentile。

| 指标 | 优化前 | 优化后 | 变化 |
|---|---:|---:|---:|
| P50 ms | 31.539 | 25.666 | -18.6% |
| P95 ms | 36.932 | 30.257 | -18.1% |
| P99 ms | 42.338 | 36.926 | -12.8% |
| 平均 ms | 32.730 | 26.562 | -18.8% |
| 请求/秒 | 30.549 | 37.640 | +23.2% |
| 调用线程 CPU ms/请求 | 32.698 | 26.549 | -18.8% |
| 调用线程分配 MiB/请求 | 114.294 | 76.138 | -33.4% |

| JVM | P50 ms | P95 ms | P99 ms | 请求/秒 | 分配 MiB/请求 |
|---|---:|---:|---:|---:|---:|
| 优化前 1 | 31.539 | 36.932 | 44.149 | 30.533 | 114.294 |
| 优化前 2 | 31.358 | 35.988 | 39.078 | 30.901 | 114.293 |
| 优化前 3 | 31.552 | 37.250 | 42.338 | 30.549 | 114.294 |
| 仅整数路径，单次探查 | 29.811 | 38.726 | 52.840 | 32.276 | 83.462 |
| 合并优化 1 | 25.475 | 30.257 | 35.088 | 37.803 | 76.138 |
| 合并优化 2 | 25.666 | 30.217 | 36.926 | 37.640 | 76.138 |
| 合并优化 3 | 25.997 | 31.231 | 42.991 | 36.772 | 76.137 |

单项探查显示整数路径明显减少分配，但只测了一个 JVM，不能从其尾延迟推导独立收益。前后测量未交错，仍包含 JIT 和桌面背景变化；P99 样本少且范围重叠，不承诺线上尾延迟改善比例。当前负载为整数加乘；浮点为主或异常频繁的负载需另测，不能直接套用上述收益。未使用昨天的较慢数据作为分母。

所有 8 个 JVM（含后述采样）都使用完全相同的特征配置文件（SHA-256 一致），计时前后完整输出通过独立 oracle 校验。7 个无采样 JVM 的逐请求 CSV 条数和平均值与摘要一致。

## 新火焰图

合并优化后独立采样 1 个 JVM，预热 15 秒、测量 30 秒。采样开销不混入上表。JFR 保留请求栈 Java 执行样本 1,239 个；当前整数负载中 `BigDecimal`、`arithmeticOperand` / `asPreciseDecimal` 未出现在保留的 CPU/分配热点中，和被优化的路径相符。这是采样结果，不表示所有业务类型都不再使用 BigDecimal。

剩余主要 CPU leaf 样本（互不重叠）：

| 方法 | 占保留 Java 执行样本 |
|---|---:|
| `HashMap.putVal` | 14.85% |
| `DagRuntime.evaluateBatch` | 11.38% |
| `SingleLoopBatchOperatorKernel.evaluateBatch` | 9.77% |
| `HashMap.getNode` | 7.75% |
| `SliceByIndicesOperator.slice` | 5.73% |
| `Arrays.copyOf` | 4.60% |

分配采样权重主要集中在 Object 数组（29.76%）、LinkedHashMap.Entry（24.03%）、HashMap 桶数组（12.14%）和 ArrayList（11.98%）。这些是抽样权重估计，不是存活内存或精确分配计数。后续可优先评估通用 Batch 参数/结果容器和输出 Map 构建成本；没有在本轮引入额外缓存或改变容器所有权契约。

测量录制含 50 次 GC 暂停，总计 0.6778 ms、最大 0.0265 ms，无 ZAllocationStall。CPU 图只包含请求 Java 栈，不包含完整 GC/native 线程 CPU。

- [交互式 CPU/分配火焰图](artifacts/zgc-hotspot-20260924/flamegraphs.html)
- JFR 原始记录：本地 `target/zgc-optimization-20260924/profile-after/run-1/measurement.jfr`
- [前后对照机器可读汇总](artifacts/zgc-hotspot-20260924/comparison.json)

## 验证

- 新增 `IntegralArithmeticFastPathTest` 4 项：四种整数载体与 long 边界、固定种子随机数对对照 BigDecimal oracle、宽 BigInteger 抵消/乘零、浮点及混合类型语义、非有限输入、序列广播及错误位置、Batch 逐行恢复与 Single 一致。
- `SequenceOutputMaterializationTest` 新增未知输出名的异常类型和文本检查。新增语义测试在改动前后均通过；性能问题用端到端分配计数/耗时复现和验证，没有给普通 UT 添加不稳定的计时阈值。
- 相关数值算子测试、输出物化测试、`LargeOnlineWorkloadTest` 均通过；只读代码审查未发现正确性问题。
- 三个修改过的 `operator.builtin` 文件以 `javac --release 8` 对当前项目编译依赖通过检查；这只验证这些源码的语法/API 基线，不表示整个项目可在 Java 8 运行。
- 完整 `mvn test`：261 项、8 失败、0 错误。8 个失败方法与优化前保存的日志完全一致，均为既有 365d 业务输出预期差异；全量回归仍未全绿。
- 提交前另外导出只包含本 PR 文件的干净索引快照，重新运行自测及 Maven：259 项、同样 8 失败、0 错误。该快照不含本地其他任务的两个性能测试，失败方法集合完全相同。
- 显式运行 `scripts/run-self-test.sh`：仍在冻结自测的注册表数量断言失败（预期 20，实际 24）。未修改冻结自测。
- CPU/分配 SVG 可被 XML 解析，矩形宽度有效；`git diff --check` 通过。

日志、每次请求耗时、GC 日志和采样保存在 `target/zgc-optimization-20260924/`。`target` 会被 `mvn clean` 清理；本报告保留对照方法、汇总与主要发现。PR 附带过滤后的火焰图、逐轮摘要及原始时延 CSV；完整 JFR 留在本地。

## 复现优化后结果

在仓库根目录的 PowerShell 中执行，需 JDK 21、Maven 和 Python（标准库即可）。每次选择新的输出目录，脚本拒绝覆盖既有 run。

```powershell
./scripts/run-large-online-performance.ps1 -Runs 3 -Heap 8g -OutputDirectory target/reproduce-hotspot
./scripts/run-large-online-performance.ps1 -Runs 1 -Heap 8g -Profile -OutputDirectory target/reproduce-hotspot-profile
mvn '-Dtest=IntegralArithmeticFastPathTest,SequenceOutputMaterializationTest,LargeOnlineWorkloadTest' test
```

优化前的原始数据保存在本文相邻的 `artifacts/zgc-hotspot-20260924/before/`；复测旧版本时需将本 PR 的压测入口及辅助脚本带到旧版本的独立检出目录，保持生成器和 JVM 参数一致。
