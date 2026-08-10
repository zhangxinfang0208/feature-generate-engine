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
        Objects.requireNonNull(nodeState, "nodeState");
        values.put(key, value);
        mutableStats(kind).recordPut();
        nodeState.recordCachePut(kind);
    }

    public int size() {
        return values.size();
    }

    public Map<CacheKind, CacheStats> snapshot() {
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

    private static final class MutableCacheStats {
        private long lookups;
        private long hits;
        private long misses;
        private long puts;

        private void recordLookup(boolean hit) {
            lookups++;
            if (hit) {
                hits++;
            } else {
                misses++;
            }
        }

        private void recordPut() {
            puts++;
        }

        private CacheStats snapshot() {
            return new CacheStats(lookups, hits, misses, puts);
        }
    }
}
