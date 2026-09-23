package com.example.featuredag.api;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class GenerateResult {
    private final String executionId;
    private final Map<String, List<?>> featureValues;
    private final List<Map<String, List<?>>> candidateFeatureValues;

    public GenerateResult(
            String executionId,
            Map<String, List<?>> featureValues,
            List<Map<String, List<?>>> candidateFeatureValues) {
        this(executionId, featureValues, candidateFeatureValues, false);
    }

    /**
     * API 编码边界（C1/C9）：接管引擎独占的结果容器，不再次复制已经冻结的编码值。
     * 调用者必须提供私有可变的候选行列表、非空键和不可变编码值；交付后不得再读写容器。
     * 该入口仅限包内引擎调用；公共构造器始终保留防御复制语义。
     */
    static GenerateResult fromOwnedEncodedValues(
            String executionId,
            Map<String, List<?>> featureValues,
            List<Map<String, List<?>>> candidateFeatureValues) {
        return new GenerateResult(executionId, featureValues, candidateFeatureValues, true);
    }

    private GenerateResult(
            String executionId,
            Map<String, List<?>> featureValues,
            List<Map<String, List<?>>> candidateFeatureValues,
            boolean ownedEncodedValues) {
        this.executionId = Objects.requireNonNull(executionId, "executionId");
        if (ownedEncodedValues) {
            this.featureValues = Collections.unmodifiableMap(featureValues);
            for (int index = 0; index < candidateFeatureValues.size(); index++) {
                candidateFeatureValues.set(index,
                        Collections.unmodifiableMap(candidateFeatureValues.get(index)));
            }
            this.candidateFeatureValues = Collections.unmodifiableList(candidateFeatureValues);
        } else {
            this.featureValues = FeatureValueCollections.immutableFeatureMap(featureValues);
            this.candidateFeatureValues = FeatureValueCollections.immutableCandidates(candidateFeatureValues);
        }
    }

    public String executionId() { return executionId; }
    public Map<String, List<?>> featureValues() { return featureValues; }
    public List<Map<String, List<?>>> candidateFeatureValues() { return candidateFeatureValues; }
}
