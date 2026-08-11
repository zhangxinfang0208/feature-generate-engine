# 运行时观测闭环

运行时观测通过 `RuntimeObserver` 从公共 `generate` 边界输出，不在核心链路写日志，也不让指标系统
反向影响 DAG 执行。默认观察器是 `RuntimeObserver.NOOP`；默认路径不会构造诊断快照。

## 接入方式

```java
InMemoryRuntimeObserver observer = new InMemoryRuntimeObserver(100);
InitOptions options = InitOptions.builder()
        .environment(ExecutionEnvironment.ONLINE)
        .planId("ranking-v1")
        .runtimeObserver(observer)
        .build();

FeatureDagEngine engine = FeatureDagEngine.init(configJson, options);
engine.generate(request);
ExecutionDiagnostics diagnostics = observer.latest();
```

`InMemoryRuntimeObserver` 是有界、线程安全的实现，只用于测试、压测断言和临时诊断；生产环境不应轮询
`latest()`。生产接入使用可热更新策略和有界异步出口：

```java
RuntimeObservabilityController controller = new RuntimeObservabilityController(
        ObservabilityOptions.builder()
                .enabled(true)
                .sampleRate(0.01)
                .captureFailuresAlways(true)
                .slowRequestThreshold(Duration.ofMillis(50))
                .detailLevel(ObservationDetailLevel.CACHE)
                .build());

AsyncRuntimeObserver observer = new AsyncRuntimeObserver(
        10_000,
        200,
        Duration.ofMillis(100),
        batch -> metricsExporter.export(batch));

InitOptions options = InitOptions.builder()
        .environment(ExecutionEnvironment.ONLINE)
        .planId("ranking-v1")
        .observabilityController(controller)
        .runtimeObserver(observer)
        .build();
```

配置中心变更时原子替换策略，不需要重建引擎：

```java
controller.setEnabled(false);
controller.update(newOptions);
```

应用退出时调用 `observer.close()` 尽量排空队列。`AsyncRuntimeObserver` 在请求线程只执行非阻塞
`offer`；队列满或关闭后增加 drop 计数，不回压 DAG。后台 sink 抛出的 `RuntimeException` 被隔离并计入
export failure。应把 `AsyncObserverStats.dropped`、`pending` 和 `exportFailures` 接入自身监控。

## 采集控制语义

- 未配置 `RuntimeObserver` 时使用 `NOOP`，不会创建观测对象或诊断快照；
- `enabled=false` 可在运行中立即停止新请求采集；
- 正常请求按 `planId + executionId` 做确定性采样，同一执行 ID 的采样结果稳定；
- `captureFailuresAlways=true` 时失败请求绕过采样；
- 超过 `slowRequestThreshold` 的请求绕过采样，阈值为 `Duration.ZERO` 时关闭慢请求强制采集；
- 策略在请求开始时读取一次，运行中的配置更新从后续请求生效，单次请求不会混用两份策略；
- 当采样率为 0 且失败、慢请求强制采集都关闭时，不创建观测对象。

明细级别控制快照成本：

- `BASIC`：状态、阶段耗时、请求规模、序列规模和计划规模；
- `CACHE`：在 BASIC 基础上增加按 `CacheKind` 汇总的 lookup/hit/miss/put；
- `NODE`：在 CACHE 基础上增加逐物理节点的执行状态、耗时、去重、缓存和算子调用路径快照。

为兼容原有调用，配置自定义 Observer 但未显式传入策略时，默认是 `enabled=true`、100% 采样、失败全量、
慢请求强制采集关闭、`NODE` 明细。现网必须显式设置生产策略。Observer 回调抛出的 `RuntimeException`
会被公共 API 隔离，不会改变 `generate` 的结果。

## 诊断模型

`ExecutionDiagnostics` 记录：

- plan、feature set、version、execution、环境和成功/失败状态；
- 是否被正常采样、是否属于慢请求，以及本次快照明细级别；
- decode、runtime、encode 和端到端耗时；
- group、candidate、offline row、源序列数量、源序列元素总量和最大源序列长度；
- 物理节点、逻辑节点和融合物理节点数量；
- 按缓存类别汇总的 lookup/hit/miss/put；
- 按物理拓扑顺序排列的 `NodeExecutionSnapshot`。

算子节点快照使用 `OperatorInvocationKind` 区分 `SINGLE`、`BATCH_NATIVE`、
`BATCH_SCALAR_ADAPTER` 和 `SPECIALIZED`。Batch 调用还记录 `batchDomain` 与 `batchRowCount`；后者是
真正提交给 Kernel 的行数，因此候选缓存场景记录的是缓存命中和批内重复剔除后的唯一 miss 数量。
非算子节点的调用方式和 Batch 域为空，Single 与 SPECIALIZED 的 Batch 行数为零。

快照不包含特征值、缓存 key、`ValueHandle`、异常消息或 `Throwable`。`executionId` 只用于采样诊断和链路关联，
不得作为长期指标标签。

失败阶段使用 `ExecutionPhase` 区分：

- `VALIDATION`：请求类型或执行环境不匹配；
- `DECODE`：公共 List 输入转换或上下文构造失败；
- `RUNTIME`：物理节点执行失败；
- `ENCODE`：内部值向公共输出转换失败。

低层直接调用 `DagRuntime.execute` 时，`ExecutionResult.cacheStats()` 与 `nodeStates()` 仍可用于诊断。

## 缓存计数语义

`RuntimeCache` 是一次 `ExecutionContext` 内的可观测缓存，缓存数据不会跨 `generate`。当前真实查找分为：

- `CANDIDATE_KEY`：通用候选算子的参数元组缓存；
- `SEQUENCE_INDEX`：具体 `SequenceValue` 的索引缓存；
- `SEQUENCE_COUNT`：具体序列视图和归一化 key 的计数缓存。

每次 `lookup` 必须且只能增加一次 hit 或 miss；计算后写入增加一次 put。`REQUEST` 表示逻辑 canonical
去重和物理 slot 的单次执行复用，不执行缓存查找，因此不计入 cache hit。`dedupInputCount - uniqueInputCount`
表示候选参数去重减少的求值次数，与跨节点缓存命中是两个不同指标。

旧的 `ExecutionContext.cacheRegistry()` 仅为源兼容保留，直接操作它不会产生统计；新增执行器必须使用
`runtimeCache().lookup/put`。

## 指标与基数约束

推荐聚合：

```text
feature_dag_generate_duration
feature_dag_runtime_duration
feature_dag_node_duration
feature_dag_executions_total
feature_dag_execution_errors_total
feature_dag_cache_lookups_total
feature_dag_cache_hits_total
feature_dag_cache_misses_total
feature_dag_dedup_inputs_total
feature_dag_unique_inputs_total
feature_dag_batch_groups
feature_dag_candidates
```

长期指标标签只使用 `planId`、environment、executorId、stage、cacheKind 和 status。physicalNodeId 可放入
采样 Trace，但在计划版本不受控时不应用作长期指标标签。用户标识、candidate key、原始特征值和完整异常消息
禁止进入指标。

## 闭环验收

每次缓存或融合改动都应完成以下闭环：

1. 自测试验证输出语义、请求隔离和精确计数；
2. JMH 对比相同输入分布下的诊断开/关、缓存开/关或融合/非融合路径；
3. 在代表性环境记录吞吐、P95/P99、alloc/op、GC 与输入规模；
4. 灰度按 planId 对比新旧版本；
5. 异常时从聚合指标下钻到采样的节点快照；
6. 优化后以相同数据集重跑，并更新性能基线。

缓存命中率本身不是验收结论。只有业务输出一致，并且单位 candidate 耗时、吞吐或分配量得到改善，
才说明优化真实有效。

仓库提供独立的 `benchmarks` Maven profile；默认构建不会引入 JMH 运行依赖。完整参数矩阵可运行：

```bash
bash ./scripts/run-benchmark.sh
```

快速验证单请求场景可运行：

```bash
bash ./scripts/run-benchmark.sh \
  'FeatureDagEngineBenchmark.onlineSingle -p candidateCount=10 -p distinctKeyCount=1 -p observabilityMode=OFF'
```

默认结果写入 `target/jmh-result.json`，正式基线应保留 fork、预热和 GC profiler，不应使用非 fork 的冒烟结果。
