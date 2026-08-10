package com.example.featuredag.api;

import java.util.List;
import java.util.Objects;

/** 在线分组批结果；groupResults 与请求 groups 按下标一一对应。 */
public final class OnlineBatchGenerateResult {
    private final String executionId;
    private final List<GenerateResult> groupResults;

    public OnlineBatchGenerateResult(
            String executionId,
            List<GenerateResult> groupResults) {
        this.executionId = Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(groupResults, "groupResults");
        this.groupResults = List.copyOf(groupResults);
    }

    public String executionId() { return executionId; }
    public List<GenerateResult> groupResults() { return groupResults; }
}
