# 按目标图过滤输入与开启诊断

## 1. 初始化后获取源输入键

`FeatureDagEngine` 提供三个初始化时计算、可并发读取的不可变集合，调用接口不遍历图，也不复制集合：

| 接口 | 用途 |
| --- | --- |
| `requiredInputNames()` | 所有可达源输入；离线每行按此过滤 |
| `requiredSharedInputNames()` | 在线 `sharedValues` 输入；源实体域不含 ITEM |
| `requiredCandidateInputNames()` | 在线每个 candidate 输入；源实体域包含 ITEM |

集合来自 `targetFeatures` 构建后的逻辑图，与解码器使用同一源节点语义。包含传递依赖及带默认值的源，不包含衍生中间节点或不可达源。这里 required 表示图依赖，不表示带默认值的字段也必须由请求提供。包含 ITEM 的混合实体域归候选输入，与当前解码器一致。无源的常量图返回空集合。

返回键是请求 Map 的键：当前配置使用 `name`，`raw_name` 仅保留历史兼容。若线上字段名称不同，先映射到请求键，再检查集合，最后转换值。

在现有桥接循环中，把判断放在 `toFeatureList` 等装箱/复制操作之前：

```java
Set<String> needed = engine.requiredSharedInputNames();
Map<String, List<?>> shared = new LinkedHashMap<>();
for (Map.Entry<String, Object> entry : userFeatures.entrySet()) {
    // 按接入协议映射；这里约定未配置映射的字段同名直通。
    String inputName = fieldNameMapping.getOrDefault(entry.getKey(), entry.getKey());
    if (!needed.contains(inputName)) continue;
    shared.put(inputName, toFeatureList(entry.getValue())); // 复用业务现有转换方法
}
```

候选字段同样过滤，但使用 `requiredCandidateInputNames()`。保持候选列表长度和顺序，即使某一行过滤后是空 Map，也不能丢掉该行。线上字段到请求键的映射应唯一，避免覆盖。如果源 Map 很大，可在初始化时建立所需请求键到线上字段的反向映射，每次只遍历所需键进行查找。

引擎和依赖集合一起复用；切换配置版本时一起切换。默认目标集合仍遵守现有配置规则；要缩小图的输出范围，应在初始化时显式设置 `targetFeatures`。该接口不自动删除原本声明为目标的特征。

## 2. 本地查看一次诊断

以下片段使用 `api`、`runtime` 包中的类型以及 `java.time.Duration` 等 JDK 类型。

```java
InMemoryRuntimeObserver observer = new InMemoryRuntimeObserver(100);
InitOptions options = InitOptions.builder()
        .environment(ExecutionEnvironment.ONLINE)
        .planId("ranking-v1")
        .runtimeObserver(observer)
        .observabilityOptions(ObservabilityOptions.builder()
                .enabled(true)
                .sampleRate(1.0)
                .detailLevel(ObservationDetailLevel.NODE)
                .build())
        .build();
FeatureDagEngine engine = FeatureDagEngine.init(configJson, options);
engine.generate(request);
ExecutionDiagnostics d = observer.latest();
System.out.println("decode ms=" + d.decodeDurationNanos() / 1_000_000.0);
System.out.println("runtime ms=" + d.runtimeDurationNanos() / 1_000_000.0);
System.out.println("encode ms=" + d.encodeDurationNanos() / 1_000_000.0);
d.nodes().stream()
        .sorted(java.util.Comparator.comparingLong(NodeExecutionSnapshot::durationNanos).reversed())
        .limit(10)
        .forEach(n -> System.out.println(n.physicalNodeId()
                + " ms=" + n.durationNanos() / 1_000_000.0
                + " route=" + n.operatorInvocationKind()));
```

`latest()` 适用于本地顺序调用，线上并发请求不能用它关联当前请求。线上应通过回调中的 `executionId` 关联。

## 3. 线上采样与热更新

初始化时同时接入 Observer 和 Controller；仅设置策略、Observer 仍为默认 NOOP，不会输出诊断。

```java
// exportBatch 是业务提供的 Consumer<List<ExecutionDiagnostics>>，连接监控/诊断平台。
RuntimeObservabilityController controller = new RuntimeObservabilityController(
        ObservabilityOptions.builder()
                .enabled(true)
                .sampleRate(0.01)
                .captureFailuresAlways(true)
                .slowRequestThreshold(Duration.ofMillis(50)) // 根据业务 SLA 调整
                .detailLevel(ObservationDetailLevel.NODE)
                .build());
AsyncRuntimeObserver observer = new AsyncRuntimeObserver(
        10_000, 200, Duration.ofMillis(100), exportBatch);
FeatureDagEngine engine = FeatureDagEngine.init(configJson, InitOptions.builder()
        .environment(ExecutionEnvironment.ONLINE)
        .planId("ranking-v1")
        .runtimeObserver(observer)
        .observabilityController(controller)
        .build());

// 以下操作由配置更新触发，作用于后续请求，无需重新构建图。
controller.setEnabled(false);
controller.setEnabled(true);
controller.update(controller.options().toBuilder()
        .sampleRate(0.005).detailLevel(ObservationDetailLevel.CACHE).build());

// 服务退出生命周期中执行，不要每个请求创建/关闭 observer。
observer.close();
```

已有引擎若初始化时未接入 Observer，需用新 InitOptions 初始化并替换引擎；不能只通过 Controller 把 NOOP 变成实际出口。外部应保存 Controller 引用以供配置中心更新。

NODE 才含逐节点数据；CACHE 只有基础数据加缓存统计。Async 只异步导出，快照仍在请求线程构造。慢请求/失败绕过采样，故故障期间采集量可能上升；监控 `observer.stats()` 中的 pending、dropped、exportFailures。混合采样快照不能直接当作全量请求分位数或错误率。

性能诊断无需开启 `ConsoleRuntimeTraceObserver`。值 trace 独立于上述采样策略，且同步展开值、转字符串后才截断；更适合本地中间值调测。

业务输入封装、结果写回不在引擎 decode/runtime/encode 计时范围内，应在调用侧另行计时。详见 [运行时观测闭环](runtime-observability.md)。
