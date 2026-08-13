package com.example.featuredag.physical;

/**
 * 缓存策略。REQUEST 与 CANDIDATE_KEY 为预留枚举：
 * REQUEST 由规划器授予在线请求共享节点，但运行时一期不消费，仅记录计划意图；
 * CANDIDATE_KEY 仅供融合改写（CountAfterKeyedSequenceFilterRule）标注融合执行器节点，
 * 通用执行路径不再产生该策略。
 */
public enum CachePolicy {
    NONE,
    ROW,
    BATCH,
    USER_GROUP,
    REQUEST,
    CANDIDATE_KEY,
    PARTITION
}
