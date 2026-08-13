package com.example.featuredag.runtime;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 单次 ExecutionContext 内的可观测缓存。
 * 缓存值仍保持请求隔离，同时统一记录 lookup/hit/miss/put，避免执行器各自维护计数。
 */
public final class RuntimeCache {
    private final Map<Object, Object> values = new LinkedHashMap<>();
    private final EnumMap<CacheKind, MutableCacheStats> stats = new EnumMap<>(CacheKind.class);

    public CacheLookup lookup(
            CacheKind kind,
            Object key,
            RuntimeNodeState nodeState) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(nodeState, "nodeState");
        // 必须用 containsKey 区分“命中且值为 null”和“未命中”，两者的统计与执行语义不同。
        boolean hit = values.containsKey(key);
        mutableStats(kind).recordLookup(hit);
        nodeState.recordCacheLookup(kind, hit);
        return new CacheLookup(hit, hit ? values.get(key) : null);
    }

    public void put(
            CacheKind kind,
            Object key,
            Object value,
            RuntimeNodeState nodeState) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(nodeState, "nodeState");
        // 所有缓存类别共享请求级存储，但 key 自身携带节点、域、序列视图和变化输入以隔离语义。
        values.put(key, value);
        mutableStats(kind).recordPut();
        nodeState.recordCachePut(kind);
    }

    public int size() {
        return values.size();
    }

    public Map<CacheKind, CacheStats> snapshot() {
        // 执行结束时才冻结统计，避免对外暴露内部可变计数器。
        if (stats.isEmpty()) return Map.of();
        EnumMap<CacheKind, CacheStats> result = new EnumMap<>(CacheKind.class);
        for (Map.Entry<CacheKind, MutableCacheStats> entry : stats.entrySet()) {
            result.put(entry.getKey(), entry.getValue().snapshot());
        }
        return Collections.unmodifiableMap(result);
    }

    /** 兼容旧的诊断入口；核心执行器必须通过 lookup/put 才能产生准确统计。 */
    Map<Object, Object> valuesView() {
        return values;
    }

    private MutableCacheStats mutableStats(CacheKind kind) {
        return stats.computeIfAbsent(kind, ignored -> new MutableCacheStats());
    }

    public record CacheLookup(boolean hit, Object value) {}
}
