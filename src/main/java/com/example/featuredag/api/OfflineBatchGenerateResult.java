package com.example.featuredag.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;

/** 离线批执行结果；rows 与请求行严格按下标一一对应。 */
public final class OfflineBatchGenerateResult {
    private final String executionId;
    private final List<Map<String, List<?>>> rows;

    public OfflineBatchGenerateResult(
            String executionId,
            List<? extends Map<String, ? extends List<?>>> rows) {
        this(executionId, rows, false);
    }

    public String executionId() { return executionId; }
    public List<Map<String, List<?>>> rows() { return rows; }

    /** C1/C9：接管引擎私有结果容器，值已经编码冻结；公开构造器仍提供快照。 */
    static OfflineBatchGenerateResult fromOwnedEncodedValues(
            String executionId, List<Map<String, List<?>>> rows) {
        return new OfflineBatchGenerateResult(executionId, rows, true);
    }

    private OfflineBatchGenerateResult(
            String executionId,
            List<? extends Map<String, ? extends List<?>>> rows,
            boolean ownedEncodedValues) {
        this.executionId = Objects.requireNonNull(executionId, "executionId");
        if (ownedEncodedValues) {
            // true 仅由接收精确类型独占容器的包内工厂传入；公共入口始终走快照分支。
            @SuppressWarnings("unchecked")
            List<Map<String, List<?>>> ownedRows = (List<Map<String, List<?>>>) rows;
            for (int index = 0; index < ownedRows.size(); index++) {
                ownedRows.set(index, Collections.unmodifiableMap(ownedRows.get(index)));
            }
            this.rows = Collections.unmodifiableList(ownedRows);
        } else {
            this.rows = FeatureValueCollections.immutableFeatureRows(rows);
        }
    }
}
