package com.example.featuredag.runtime;

/** 单一缓存类别在一次执行内的不可变统计快照。 */
public record CacheStats(
        long lookups,
        long hits,
        long misses,
        long puts) {

    public CacheStats {
        if (lookups < 0 || hits < 0 || misses < 0 || puts < 0) {
            throw new IllegalArgumentException("Cache counters must not be negative");
        }
        if (hits + misses != lookups) {
            throw new IllegalArgumentException("Cache hits and misses must equal lookups");
        }
    }

    public double hitRate() {
        return lookups == 0 ? 0.0 : (double) hits / lookups;
    }
}
