package com.example.featuredag.physical;

public enum CachePolicy {
    NONE,
    ROW,
    BATCH,
    USER_GROUP,
    REQUEST,
    CANDIDATE_KEY,
    PARTITION
}
