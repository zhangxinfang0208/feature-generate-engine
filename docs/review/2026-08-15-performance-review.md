# 性能审查报告：feature-dag-engine

| 项 | 内容 |
|---|---|
| 审查日期 | 2026-08-15（第三版：已同步修复状态） |
| 审查范围 | 第一轮：`DagRuntime.widenIntegralFeatureOutput`/`widenBatchValues`（DOUBLE 边界定宽实现）；第二轮：全仓性能扫描，聚焦 `runtime/`、`physical/`、`planning/` 三层与 `operator/builtin` 算子实现 |
| 基线 | 当前分支 `agent/add-arithmetic-operators`，HEAD `bc5d548`（支持业务算子扩展注册）+ 本次会话对以下文件的未提交修复 |
| 方式 | 人工代码走读 + 一次多 agent 后台性能专项扫描（`code-review` 技能，high 强度，只找性能问题）+ 逐条复核校验 + 按复核结论修复 |
| 结论 | 第一轮发现的 2 处、第二轮扫描发现的 10 处中的 7 处均已修复，`run-self-test.sh` 全量通过（legacy 自测 + 全部 JUnit 4）。3 处明确不修：#2（新发现的正确性风险）、#3/#5（复核阶段已判定不适合现在改，理由见下） |
| 分支状态说明 | 本分支 `InitialBusinessOperators` 已注册 16 个算子，而 `AGENTS.md` 仍写"标准注册表严格只提供 8 个算子"——这是分支偏离仓库规范文档的既有状态，与本次性能扫描本身无关，仅作背景说明 |

---

## 1. 已修复问题（第一轮）

背景：`2089bc8`（修复特征边界语义与性能）在 `FEATURE_OUTPUT` 边界引入了 C6 声明 DOUBLE / 推断 INT 场景下的运行时整型定宽逻辑（`DagRuntime.widenIntegralFeatureOutput`），设计上采用 copy-on-write 避免不必要拷贝。走读该实现时发现两处未达到设计意图的低效点，已修复。

### 1.1 `CandidateVectorValue` 分支多拷贝一次

**位置**：`DagRuntime.java`，`widenIntegralFeatureOutput` 的 `CandidateVectorValue` 分支。

**修复**：改为对 `widenScalars` 已知的实际运行时类型做一次 `@SuppressWarnings("unchecked")` 强转后直接构造，去掉多余的 `new ArrayList<>(widened)`。

### 1.2 序列去重缓存未覆盖"确认无需改写"的情况

**位置**：`DagRuntime.java`，`widenBatchValues` 的 SEQUENCE 元素形状分支。

**修复**：增加一个 O(1) 的"上一个引用"直连缓存覆盖连续复用场景；只有本批已经确认存在需要改写的序列（Map 已分配）时，才顺带缓存"确认无需改写"的结果，避免完全干净的批次为此额外付出 Map 分配开销。

---

## 2. 扫描发现（经复核修正，7 处已修复）

对整个代码库做的一次性能专项扫描。**以下发现均来自静态代码走读，没有配套 profiling/JMH 数据**；"影响面"是按调用位置和频率做的定性推断，动手前的判断依据是复核后的技术结论，不是测量结论。

| # | 位置 | 复核后的问题描述 | 状态 | 修复方式 |
|---|---|---|---|---|
| 1 | `runtime/DagRuntime.java` 等 | `executeSource`/`evaluateBatch` 构建好的结果列表，塞进 `OfflineBatchValue`/`RequestBatchValue`/`CandidateBatchValue`/`CandidateVectorValue` 时构造器又整体拷贝一次；范围限定在批/候选域执行（`SOURCE_BINDING`/`GENERIC_OPERATOR` 走批量分支，以及 `wrap()` 里 CANDIDATE_VECTOR 形状那支），不含单请求标量路径和 `FEATURE_OUTPUT` 透传 | **已修复** | 给 `OfflineBatchValue`/`RequestBatchValue`/`CandidateBatchValue` 加包内信任工厂 `owned(...)`，跳过防御拷贝（公开构造器保持不变）；所有"结果刚构建、后续不再持有引用"的调用点切到 `owned(...)`；`CandidateVectorValue`（record，无法加信任构造器）的多余预拷贝改为直接强转传入 |
| 2 | `operator/SingleLoopBatchOperatorKernel.java:20` | 默认标量适配批循环对每一行都新建一个 `ArrayList` 装载参数，12+ 个内置算子走这条默认路径 | **不修复** | 复核确认代码事实成立，但修复方案（跨行复用同一个可变 buffer）在实现阶段发现新风险：本分支已支持算子扩展注册（`bc5d548`），`SingleOperatorKernel.evaluate(List<Object> arguments)` 接口未约定实现方不能持有传入的 list——内置算子都不持有，自定义扩展算子不保证遵守同一约定，跨行复用 buffer 可能造成扩展算子的正确性问题，故保留原样 |
| 3 | `operator/builtin/OperatorSupport.java:64`（`asPreciseDecimal`） | 只有 `Float`/`Double` 操作数经过字符串往返，`Byte/Short/Integer/Long` 走 `BigDecimal.valueOf(long)` 不解析字符串，但仍有 `BigDecimal` 对象分配开销 | **不修复** | 复核阶段判定：引入基本类型快路径需要仔细验证精度语义（十进制比较、溢出边界）不被破坏，风险高于本轮其余项，未安排在本次修复范围内 |
| 4 | `runtime/SequenceKeyIndex.java:22`，`IndexValue.java:15` | 先把匹配位置装箱进 `List<Integer>`，再 `stream().mapToInt().toArray()` 拆箱，`IndexValue` 构造器还要对结果 `int[]` 再拷贝一次 | **已修复** | `SequenceKeyIndex.build` 改用无装箱的可增长 int 缓冲区（内部类 `IntAccumulator`）直接累积 `baseIndex`；`IndexValue` 加包内信任工厂 `owned(...)` 跳过对每个 `int[]` 的防御拷贝 |
| 5 | `runtime/SequenceView.java:28`（`filterByColumn`） | 收集匹配的 base index 时先装箱再转 `int[]`；经确认标准注册表没有任何算子声明 `KeyedSequenceFilterSemantic`，该路径当前只被单测直接调用，**生产物理计划不可达** | **不修复** | 非现役热路径，属于潜在自定义算子路径的分配优化点；若未来落地 keyed-filter 算子实现，再一并评估 |
| 6 | `planning/LogicalDagOptimizer.java:98`（`computeReachableRoots`） | 当前实现对每个根/目标特征在共享祖先子图上各自独立跑一次 DFS，最坏复杂度 O(根数 R × (V+E))；输出本身最坏情况下总大小是 Θ(R×V)，任何精确算法都受此下界约束，"改进到 O(V+E)"是错误目标 | **已修复** | 改成一次反拓扑传播：`topologicalOrder()` 是生产者在前、消费者在后，反向遍历时每个节点在自身及全部消费者贡献的可达根集合确定后，才并入它自己的输入节点，避免对共享子图重复遍历；渐进复杂度仍受输出规模约束，只降低常数因子 |
| 7 | `operator/ListBatchColumn.java:14` | 构造器无条件防御拷贝；调用方不止四个原生 Batch kernel，`SingleLoopBatchOperatorKernel`（覆盖 12+ 个算子）和一个 Demo 也直接构造它 | **已修复** | 加包内信任工厂 `owned(...)`；四个原生 Batch kernel（`find_indices`/`count_distinct`/`zip_concat`/`calc_delta_seq`）和 `SingleLoopBatchOperatorKernel` 切到 `owned(...)`；Demo 里的调用未动，仍走公开的拷贝构造器（Demo 不在性能敏感路径上，且 Demo 所在包访问不到包内信任工厂） |
| 8 | `runtime/RuntimeCache.java:25`；`DagRuntime.executeSource` | `RuntimeCache.lookup()` 先 `containsKey` 再 `get`，同一个 key 两次哈希；`executeSource` 里行/候选/分组 `Map<String, Object>` 读取的是外部输入数据，允许"key 存在但值为 null"，不能套用同一修法 | **已修复（仅 ①）** | `RuntimeCache.put()` 本就禁止 null 值，`lookup()` 改成一次 `get()`、null 即 miss；`executeSource` 的 `containsKey` 按原样保留，未改动 |
| 9 | `runtime/DagRuntime.java:396`（`evaluationDomain`） | 对同一个（通常很短的）`inputHandles` 列表做四次独立的 `stream().anyMatch(X.class::isInstance)`；是否每次都实际分配 lambda 取决于 JIT 逃逸分析，收益未测量但改动本身低风险 | **已修复** | 改成一轮 `instanceof` 分类循环，去掉四次 Stream 流水线构造，机械化改写、无行为变化 |
| 10 | `operator/builtin/ZipConcatOperator.java:73` | `evaluateBatch` 检查复用缓存前，命中/未命中都要先构建查 key 用的对象；重复行的分配是 `ArrayList` + `ZipBatchKey` 对象 + 内部 `Object[]`，三个而非两个 | **已修复** | 每行直接建 `Object[]` 交给改造后的 `ZipBatchKey`（按引用持有，不再 `toArray()`），命中/未命中缓存都从 3 个对象降到 2 个；`zipSequences` 需要的 `List` 用 `Arrays.asList(...)` 轻量包装，不产生额外拷贝 |

**排除项**：后台 agent 排查后**排除**了 `DiscreteOperator.toBoundaries()` 每行重新解析边界这一疑似问题——其类级 Javadoc 记录了这是实测过的有意权衡（曾尝试缓存导致 Batch 变慢），符合 `AGENTS.md` "不得在未重新测量成本模型的情况下引入原生 Batch" 的约束，不算真正的问题。

---

## 3. 验证

`bash scripts/run-self-test.sh` 全量通过（legacy `java -ea` 自测 + 全部 JUnit 4，含 `alreadyDoubleBatchKeepsFeatureBoundaryZeroCopy` 零拷贝回归测试）。所有改动都限定在包内信任工厂 + 机械化改写的范围内，公开 API 契约未变。

## 4. 未修复项与后续建议

- **#2**：不建议在不改变 `SingleOperatorKernel` 契约（例如显式声明"kernel 承诺不持有 arguments 引用"）的前提下做跨行 buffer 复用；如果要修，应该先在算子协议里加这条约束并在扩展注册路径上强制校验。
- **#3**：BigDecimal 快路径收益可能最大，但需要专门验证精度/溢出语义，建议单独排期并配合针对性单测（尤其是 Long 边界值、精确十进制比较相等的场景）。
- **#5**：`SequenceView.filterByColumn` 当前不可达，等有 keyed-filter 算子实现落地后再评估。
- 本报告所有判断仍然缺少 profiling/JMH 数据支撑；建议后续用实际负载（离线批 / 在线候选）跑一次基准，验证这些修复的实际收益量级。
