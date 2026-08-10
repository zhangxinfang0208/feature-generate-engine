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

生产环境应实现线程安全、非阻塞的 `RuntimeObserver` 适配器，把一次执行的局部计数批量写入指标系统。
适配器抛出的 `RuntimeException` 会被公共 API 隔离，不会改变 `generate` 的结果。`InMemoryRuntimeObserver`
是有界、线程安全的实现，只用于测试、压测断言和临时诊断。

## 诊断模型

`ExecutionDiagnostics` 记录：

- plan、feature set、version、execution、环境和成功/失败状态；
- decode、runtime、encode 和端到端耗时；
- group、candidate、offline row、源序列数量、源序列元素总量和最大源序列长度；
- 物理节点、逻辑节点和融合物理节点数量；
- 按缓存类别汇总的 lookup/hit/miss/put；
- 按物理拓扑顺序排列的 `NodeExecutionSnapshot`。

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
  'FeatureDagEngineBenchmark.onlineSingle -p candidateCount=10 -p distinctKeyCount=1 -p diagnosticsEnabled=false'
```

默认结果写入 `target/jmh-result.json`，正式基线应保留 fork、预热和 GC profiler，不应使用非 fork 的冒烟结果。
