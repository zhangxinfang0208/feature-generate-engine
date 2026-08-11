package com.example.featuredag.runtime;

/** 运行时真实发生查找的缓存类别；请求共享 slot 复用不计作缓存命中。 */
public enum CacheKind {
    CANDIDATE_KEY,
    SEQUENCE_INDEX,
    SEQUENCE_COUNT
}
