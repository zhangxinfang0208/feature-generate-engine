package com.example.featuredag.runtime;

import com.example.featuredag.physical.ExecutionEnvironment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单次执行的上下文（运行时）：承载输入（共享源值 + 候选表）、
 * 输出槽表（物理节点结果按 slot:N 写入）、缓存注册表与节点状态表；
 * 生命周期与一次 generate 调用一致。
 */
public final class ExecutionContext {
    private final String executionId;
    private final ExecutionEnvironment environment;
    private final Map<String, Object> sharedSourceValues;
    private final List<Map<String, Object>> candidates;
    private final List<Map<String, Object>> offlineRows;
    private final boolean offlineBatch;
    private final Map<String, ValueHandle> resultSlots = new LinkedHashMap<>();
    private final Map<Object, Object> cacheRegistry = new LinkedHashMap<>();
    private final Map<String, RuntimeNodeState> nodeStates = new LinkedHashMap<>();

    private ExecutionContext(
            String executionId,
            ExecutionEnvironment environment,
            Map<String, Object> sharedSourceValues,
            List<Map<String, Object>> candidates,
            List<Map<String, Object>> offlineRows,
            boolean offlineBatch) {
        this.executionId = Objects.requireNonNull(executionId, "executionId");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.sharedSourceValues = Collections.unmodifiableMap(new LinkedHashMap<>(sharedSourceValues));
        this.candidates = candidates.stream()
                .map(candidate -> Collections.unmodifiableMap(new LinkedHashMap<>(candidate)))
                .toList();
        this.offlineRows = offlineRows.stream()
                .map(row -> Collections.unmodifiableMap(new LinkedHashMap<>(row)))
                .toList();
        this.offlineBatch = offlineBatch;
    }

    public static ExecutionContext offlineRow(String executionId, Map<String, Object> rowValues) {
        return new ExecutionContext(
                executionId, ExecutionEnvironment.OFFLINE, rowValues, List.of(), List.of(), false);
    }

    public static ExecutionContext offlineBatch(
            String executionId,
            List<Map<String, Object>> rows) {
        Objects.requireNonNull(rows, "rows");
        return new ExecutionContext(
                executionId, ExecutionEnvironment.OFFLINE, Map.of(), List.of(), rows, true);
    }

    public static ExecutionContext onlineRequest(
            String requestId,
            Map<String, Object> userAndSceneValues,
            List<Map<String, Object>> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        return new ExecutionContext(
                requestId, ExecutionEnvironment.ONLINE,
                userAndSceneValues, candidates, List.of(), false);
    }

    public String executionId() { return executionId; }
    public ExecutionEnvironment environment() { return environment; }
    public Map<String, Object> sharedSourceValues() { return sharedSourceValues; }
    public List<Map<String, Object>> candidates() { return candidates; }
    public int candidateCount() { return candidates.size(); }
    public List<Map<String, Object>> offlineRows() { return offlineRows; }
    public boolean isOfflineBatch() { return offlineBatch; }
    public int offlineBatchSize() { return offlineRows.size(); }
    public Map<String, ValueHandle> resultSlots() { return resultSlots; }
    public Map<Object, Object> cacheRegistry() { return cacheRegistry; }
    public Map<String, RuntimeNodeState> nodeStates() { return nodeStates; }

    public RuntimeNodeState state(String physicalNodeId) {
        return nodeStates.computeIfAbsent(physicalNodeId, RuntimeNodeState::new);
    }

}
