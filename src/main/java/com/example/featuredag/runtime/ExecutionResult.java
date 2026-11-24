package com.example.featuredag.runtime;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 执行产物（运行时）：特征名 → 值句柄 的输出映射、各物理节点运行状态和请求级缓存统计，
 * 供引擎编码输出与低层观测诊断使用。
 */
public final class ExecutionResult {
    private final Map<String, ValueHandle> featureOutputs;
    /** 保留诊断时的节点状态快照；免诊断模式（见 withoutDiagnostics）下为 null。 */
    private final Map<String, RuntimeNodeState> nodeStates;
    private final Map<CacheKind, CacheStats> cacheStats;
    private final Supplier<Map<String, RuntimeNodeState>> liveNodeStates;
    private Map<String, RuntimeNodeState> liveView;

    public ExecutionResult(
            Map<String, ValueHandle> featureOutputs,
            Map<String, RuntimeNodeState> nodeStates) {
        this(featureOutputs, nodeStates, Map.of());
    }

    public ExecutionResult(
            Map<String, ValueHandle> featureOutputs,
            Map<String, RuntimeNodeState> nodeStates,
            Map<CacheKind, CacheStats> cacheStats) {
        this.featureOutputs = Collections.unmodifiableMap(new LinkedHashMap<>(featureOutputs));
        Map<String, RuntimeNodeState> stateSnapshots = new LinkedHashMap<>();
        for (Map.Entry<String, RuntimeNodeState> entry : nodeStates.entrySet()) {
            stateSnapshots.put(entry.getKey(), entry.getValue().snapshot());
        }
        this.nodeStates = Collections.unmodifiableMap(stateSnapshots);
        this.cacheStats = copyCacheStats(cacheStats);
        this.liveNodeStates = null;
    }

    private ExecutionResult(
            Map<String, ValueHandle> featureOutputs,
            Map<CacheKind, CacheStats> cacheStats,
            Supplier<Map<String, RuntimeNodeState>> liveNodeStates) {
        this.featureOutputs = featureOutputs;
        this.nodeStates = null;
        this.cacheStats = copyCacheStats(cacheStats);
        this.liveNodeStates = liveNodeStates;
    }

    /**
     * 免诊断执行的结果：输出映射直接采用运行时收集的顺序，不做第二次复制；
     * 节点状态在第一次访问时才从执行上下文物化为只读视图，不逐节点做快照。
     * 调用方不读取逐节点诊断时使用；公开构造器始终提供快照语义。
     */
    static ExecutionResult withoutDiagnostics(
            String[] featureNames, ValueHandle[] values, ExecutionContext context) {
        Map<String, ValueHandle> outputs = new LinkedHashMap<String, ValueHandle>();
        for (int index = 0; index < featureNames.length; index++) {
            outputs.put(featureNames[index], values[index]);
        }
        return new ExecutionResult(
                Collections.unmodifiableMap(outputs),
                context.runtimeCache().snapshot(),
                () -> Collections.unmodifiableMap(context.nodeStates()));
    }

    private static Map<CacheKind, CacheStats> copyCacheStats(Map<CacheKind, CacheStats> cacheStats) {
        if (cacheStats.isEmpty()) return Map.of();
        EnumMap<CacheKind, CacheStats> copy = new EnumMap<>(CacheKind.class);
        copy.putAll(cacheStats);
        return Collections.unmodifiableMap(copy);
    }

    public Map<String, ValueHandle> featureOutputs() { return featureOutputs; }

    /** 保留诊断时返回状态快照；免诊断时返回执行上下文中状态的一次性只读视图。 */
    public Map<String, RuntimeNodeState> nodeStates() {
        if (nodeStates != null) return nodeStates;
        Map<String, RuntimeNodeState> view = liveView;
        if (view == null) {
            view = liveNodeStates.get();
            liveView = view;
        }
        return view;
    }

    public Map<CacheKind, CacheStats> cacheStats() { return cacheStats; }

    public ValueHandle feature(String featureName) {
        ValueHandle value = featureOutputs.get(featureName);
        if (value == null) throw new IllegalArgumentException("No output feature: " + featureName);
        return value;
    }
}
