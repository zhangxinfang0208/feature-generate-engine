package com.example.featuredag.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class OnlineGenerateRequest implements GenerateRequest {
    private final String executionId;
    private final Map<String, List<?>> sharedValues;
    private final List<Map<String, List<?>>> candidates;

    public OnlineGenerateRequest(
            String executionId,
            Map<String, List<?>> sharedValues,
            List<Map<String, List<?>>> candidates) {
        this(executionId, sharedValues, candidates, false);
    }

    /**
     * 同步在线执行的只读借用入口：不复制特征 Map 或特征序列。
     * 从构造请求到最后一次 generate 返回期间，调用方必须保持所有输入及嵌套值不变，
     * 包括其他线程和通过访问器取得的引用；禁止与输入写入并发。
     * 引擎不会修改输入；输出仍为独立不可变结果，返回后可复用输入容器。
     * 需要保存输入快照时使用普通构造器。候选外层列表仍保存一份只读 Map 视图列表。
     */
    public static OnlineGenerateRequest borrowed(
            String executionId,
            Map<String, List<?>> sharedValues,
            List<Map<String, List<?>>> candidates) {
        return new OnlineGenerateRequest(executionId, sharedValues, candidates, true);
    }

    private OnlineGenerateRequest(
            String executionId,
            Map<String, List<?>> sharedValues,
            List<Map<String, List<?>>> candidates,
            boolean borrowed) {
        this.executionId = requireText(executionId, "executionId");
        Objects.requireNonNull(candidates, "candidates");
        this.sharedValues = borrowed
                ? FeatureValueCollections.borrowedFeatureMap(sharedValues)
                : FeatureValueCollections.immutableFeatureMap(sharedValues);
        this.candidates = borrowed
                ? FeatureValueCollections.borrowedFeatureRows(candidates)
                : FeatureValueCollections.immutableCandidates(candidates);
    }

    @Override
    public String executionId() { return executionId; }
    public Map<String, List<?>> sharedValues() { return sharedValues; }
    public List<Map<String, List<?>>> candidates() { return candidates; }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
