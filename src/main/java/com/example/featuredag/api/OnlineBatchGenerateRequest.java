package com.example.featuredag.api;

import java.util.List;
import java.util.Objects;

/** 在线分组批请求；每个 group 保持独立的共享输入、候选表及执行标识。 */
public final class OnlineBatchGenerateRequest {
    private final String executionId;
    private final List<OnlineRequestGroup> groups;

    public OnlineBatchGenerateRequest(
            String executionId,
            List<OnlineRequestGroup> groups) {
        this.executionId = requireText(executionId, "executionId");
        Objects.requireNonNull(groups, "groups");
        this.groups = List.copyOf(groups);
    }

    public String executionId() { return executionId; }
    public List<OnlineRequestGroup> groups() { return groups; }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
