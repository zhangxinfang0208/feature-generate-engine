package com.example.featuredag.api;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 列式输出。标量单元为值，序列单元为 List；共享输出沿用原有 List 契约。 */
public final class OnlineColumnarGenerateResult {
    private final String executionId;
    private final List<String> candidateIds;
    private final Map<String, List<?>> sharedFeatureValues;
    private final Map<String, List<?>> candidateFeatureColumns;

    // 仅限编码器移交独占容器；所有单元必须已物化并冻结。ID 快照不借用请求的可变列表。
    OnlineColumnarGenerateResult(String executionId, List<String> candidateIds,
            Map<String, List<?>> shared, Map<String, List<?>> columns) {
        this.executionId = executionId;
        this.candidateIds = List.copyOf(candidateIds);
        this.sharedFeatureValues = Collections.unmodifiableMap(shared);
        this.candidateFeatureColumns = Collections.unmodifiableMap(columns);
    }

    public String executionId() { return executionId; }
    public List<String> candidateIds() { return candidateIds; }
    public Map<String, List<?>> sharedFeatureValues() { return sharedFeatureValues; }
    public Map<String, List<?>> candidateFeatureColumns() { return candidateFeatureColumns; }
}
