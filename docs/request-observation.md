# 请求上下文控制诊断和 Trace

`InitOptions.Builder.requestObservationEnabled(BooleanSupplier)` 为每次调用提供观测总开关：

```java
InitOptions options = InitOptions.builder()
        .environment(ExecutionEnvironment.ONLINE)
        .requestObservationEnabled(logger::isDebugEnabled)
        .runtimeObserver(diagnosticsObserver)
        .runtimeTraceObserver(traceObserver)
        .build();
```

- 注册了任一非 NOOP 观察者时，每次 `generate` / `generateBatch` 在调用线程求值一次。
- 结果是方法局部变量，诊断与 Trace 共用该结果，不更新共享 Controller，也不使用引擎 ThreadLocal。
- false 跳过额外请求诊断对象、快照及 Trace 回调，仍执行原有特征计算和运行时节点状态记录。
- true 允许观测；诊断仍服从既有 ObservabilityOptions 的 enabled/采样/失败/慢请求规则。
- Trace 不服从诊断采样，但受该请求总开关限制。
- 未配置该 Supplier 时默认为 true，保持旧的观察者行为；两个观察者均为 NOOP 时不求值。
- Supplier 抛出 RuntimeException 时本次按 false 处理，观测故障不改变计算结果；不吞 JVM Error。
- 请求上下文应在调用开始前建立，返回后由业务框架清理。切换线程时由调用方传播上下文。
- 分组 Batch 按整个 generateBatch 调用者的上下文判断一次，不逐 group 判断。
- 回调同步执行；如果绑定到日志框架，最终输出仍受日志框架当时的过滤规则控制。

在线拨测可固定 `enabled=true`、`sampleRate=1.0`、`detailLevel=NODE`，由请求开关决定采集。
请传入方法引用，而不是初始化时求值的 `boolean`。无需日志级别变更通知。

验证：`RequestObservationGateTest` 覆盖默认兼容、线程与求值次数、关闭路径、异常隔离、
单独观察者、在线/离线/批量入口、失败请求和混合并发线程复用。
