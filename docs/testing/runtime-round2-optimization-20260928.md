# 第二轮公共运行时优化与复测（2026-09-28）

## 范围

在上一轮 public-hotpath 优化版上继续修改，本轮没有修改具体算子。原始 Git 基线为 06fb4d3eb7c66aadbdc3f42b21422bfe5c562444。

1. **ExecutionContext 输入交接**：新增 onlineRequestFromOwnedDecodedValues，由 FeatureDagEngine 单请求在线入口传入解码器刚创建的共享/候选 Map。省去每张 Map 的再次复制；按当前 300 候选×80 个商品源字段负载，避免重复构造约 24,000 个候选 Map 条目/请求。返回的 Map 仍不可修改，外层候选列表仍重新组装；同一个单请求共享 Map 与唯一 group 复用同一冻结包装。普通 onlineRequest 工厂仍保留防御复制。
2. **请求内批失败检查复用**：ExecutionContext 内增加惰性 IdentityHashMap，记录不可变批值句柄的首个失败或“无失败”。按引用身份识别句柄，避免 CandidateVectorValue.record 的结构 hashCode 遍历向量；每个句柄只扫描一次，记录在请求结束后一起释放。
3. **成功路径跳过逐行预检**：DagRuntime 确认全部输入批值无失败后直接使用连续行布局；发现批失败或标量失败时仍走原逐行检查、投影与散射，保留首个失败、组索引和候选位置。特征默认值边界复用相同检查记录。

### 所有权与兼容性

新增工厂因 API/runtime 跨包调用而为 public，仅用于引擎内部解码边界。调用者必须交出共享及候选 Map 的所有权，后续不得通过别名修改；不能将业务方仍会修改的 Map 直接传入。当前引擎调用点由解码器新建这些 Map，并未将业务输入直接借用。

检查记录只针对不可变批值容器。没有依据 deterministic 标记假设算子成功，也没有改变 SingleOperatorKernel 参数列表契约；未复用扩展算子可能保留的可变参数列表。扩展 BatchColumn 仍沿用原有快照规则。

## 正确性验证

- 新增 RuntimeInputAndFailureCacheTest，共 5 项：原公开工厂快照隔离、新工厂结果不可修改及外层列表隔离、失败检查仅请求内复用、四种批域的首个失败及嵌套值语义、空分组及共享失败广播/候选默认值/后续请求隔离。
- 相关测试 22 项通过，包含已有 BatchFastPathRuntimeTest、OperatorBatchFailureFallbackRuntimeTest、OutputConstructionTest 和 LargeOnlineWorkloadTest。
- 全量 JUnit：上一轮版 263 项/8 失败；本轮版 268 项/8 失败。失败方法集合一致，仍为已知 365d 业务期望差异，不代表全量回归通过。
- 冻结 DagEngineSelfTest 直接使用 java -ea 执行，仍失败于 Expected 20 operators, got 24，未修改冻结测试。
- 环境没有 Maven，使用 javac --release 21 编译全部生产代码及测试并通过 JUnitCore 运行。Jackson databind/annotations 为 2.14.0、core 为 2.14.1，与 POM 版本不同；两版测试和压测使用同一依赖。标准 Maven 验证仍待在完整环境执行。

## 性能对照

与本次重跑的上一轮优化版进行对照，不与上午另一组结果直接相除。Linux、OpenJDK 21.0.12、JVM 可见 8 CPU、-Xms3g -Xmx3g -XX:+UseZGC -XX:+ZGenerational。

320 BASE / 320 DERIVED、160 条长度 384 的序列、300 候选、8 个预构造请求轮换、单调用线程闭环、观察器关闭。只测 generate，业务 Bridge 和 OnlineGenerateRequest 构造不计入。

执行顺序 round1-1 → round2-1 → round1-2 → round2-2 → round1-3 → round2-3；每轮独立 JVM，预热 15 秒、测量 30 秒，关闭 JFR。每轮测量前后完整候选输出通过独立 oracle；六轮 features.json 哈希一致，CSV 数量与平均值已核对。

以下为逐轮指标的中位数，不是合并所有请求重算的分位数：

| 指标 | 上一轮版（本次重跑） | 本轮版 | 变化 |
|---|---:|---:|---:|
| QPS | 35.930 | 37.888 | +5.45% |
| P50 ms | 25.915 | 24.989 | -3.57% |
| P95 ms | 38.297 | 35.999 | -6.00% |
| P99 ms | 51.066 | 46.214 | -9.50% |
| 分配 MiB/请求 | 44.582 | 42.957 | -3.64% |

| 轮次 | QPS | P50 ms | P95 ms | P99 ms | 分配 MiB/请求 |
|---|---:|---:|---:|---:|---:|
| round1-1 | 37.879 | 24.953 | 36.495 | 43.705 | 44.582 |
| round2-1 | 37.888 | 24.989 | 35.999 | 46.214 | 42.972 |
| round1-2 | 34.922 | 25.915 | 45.586 | 63.516 | 44.579 |
| round2-2 | 36.831 | 25.238 | 39.991 | 48.746 | 41.582 |
| round1-3 | 35.930 | 26.339 | 38.297 | 51.066 | 44.585 |
| round2-3 | 39.771 | 23.513 | 35.081 | 45.121 | 42.957 |

环境波动明显：第一组 QPS 基本持平，P99 反而上升，后两组改善。结果支持分配量下降及本次三轮中位数改善，不保证线上稳定获得同等尾延迟收益。与上一轮优化前的累计收益未在本次直接测量。

## 新 JFR 火焰图

独立采样轮为同一负载，15 秒预热 + 30 秒采样；该采样在计时对照之后的继续会话中完成，不将其 QPS 52.41/P99 40.68 ms 混入上表。

保留请求 Java CPU 样本 1,273 个、分配样本 4,328 个。主要 CPU 叶热点：

| 方法 | 占比 |
|---|---:|
| ExternalValueMaterializer.materializeRaw | 16.50% |
| HashMap.getNode | 13.51% |
| SingleLoopBatchOperatorKernel.evaluateBatch | 12.49% |
| HashMap.putVal | 11.55% |
| SliceByIndicesOperator.slice | 7.70% |
| FeatureOutputEncoder.encodeValue | 4.32% |

ExecutionContext.firstBatchFailure 整条调用栈约 0.86%，IdentityHashMap 相关栈约 0.08%。本次未采到 DagRuntime.evaluateBatch 的 CPU 叶样本，但包含其下游的调用栈仍占 40.53%，不能表述为该方法零耗时。

主要分配类别：Object[] 34.64%、LinkedHashMap.Entry 24.17%、ArrayList 9.93%、HashMap.Node[] 7.55%。分配图为抽样权重，不是存活内存。CPU 图不包含完整 native/GC CPU，也不表示等待时间。

该采样轮无 ZAllocationStall；202 次 GC pause 合计约 2.83 ms、最大 0.084 ms。独立采样之间环境/JIT 波动大，火焰图主要用于定位剩余热点。

## 修改包

- src/：累计两轮的完整修改文件和新增测试，可覆盖到原始 06fb4d3 基线。
- cumulative-from-06fb4d3.patch：累计两轮补丁。
- round2-over-round1.patch：仅第二轮修改，适用于已应用上一轮源码包的版本。
- docs/testing/：两轮报告。
- results/：本轮六次原始计时、GC 日志、测试日志、新 JFR/CPU/分配图与交互式 HTML。

后续优化重点仍是输出物化、Map 访问、SingleLoopBatchOperatorKernel 参数分配。参数复用需要显式的不持有参数契约，输出结构调整需要评估 Map 顺序、不可变性及序列化兼容性。
