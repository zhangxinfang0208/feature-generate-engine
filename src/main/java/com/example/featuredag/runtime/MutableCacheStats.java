package com.example.featuredag.runtime;

/** Package-private mutable accumulator shared by request and node cache diagnostics. */
final class MutableCacheStats {
    private long lookups;
    private long hits;
    private long misses;
    private long puts;

    void recordLookup(boolean hit) {
        lookups++;
        if (hit) {
            hits++;
        } else {
            misses++;
        }
    }

    void recordPut() {
        puts++;
    }

    CacheStats snapshot() {
        return new CacheStats(lookups, hits, misses, puts);
    }

    MutableCacheStats copy() {
        MutableCacheStats copy = new MutableCacheStats();
        copy.lookups = lookups;
        copy.hits = hits;
        copy.misses = misses;
        copy.puts = puts;
        return copy;
    }
}
