package com.example.featuredag.runtime;

import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.Set;

/** C1/C10：已解码的稳定候选列；旧扩展执行器按需访问行视图，标准绑定路径直接读列。 */
final class ColumnarCandidateValues extends AbstractList<Map<String, Object>> implements RandomAccess {
    private final Map<String, List<?>> columns;
    private final int candidateCount;

    ColumnarCandidateValues(Map<String, List<?>> ownedColumns, int candidateCount) {
        if (candidateCount < 0) throw new IllegalArgumentException("candidateCount must be non-negative");
        ownedColumns.forEach((name, values) -> {
            Objects.requireNonNull(name, "feature name");
            if (Objects.requireNonNull(values, "column values").size() != candidateCount) {
                throw new IllegalArgumentException("Candidate column size mismatch: " + name);
            }
        });
        this.columns = Collections.unmodifiableMap(ownedColumns);
        this.candidateCount = candidateCount;
    }

    List<?> column(String name) { return columns.get(name); }

    @Override public int size() { return candidateCount; }

    @Override public Map<String, Object> get(int index) {
        Objects.checkIndex(index, candidateCount);
        return Collections.unmodifiableMap(new AbstractMap<>() {
            @Override public Object get(Object key) {
                List<?> column = columns.get(key);
                return column == null ? null : column.get(index);
            }

            @Override public boolean containsKey(Object key) { return get(key) != null; }

            @Override public Set<Entry<String, Object>> entrySet() {
                // 仅在扩展显式遍历行时物化该行；普通列式执行不进入此分支。
                Map<String, Object> row = new LinkedHashMap<>();
                columns.forEach((name, values) -> {
                    Object value = values.get(index);
                    if (value != null) row.put(name, value);
                });
                return Collections.unmodifiableMap(row).entrySet();
            }
        });
    }
}
