package com.example.featuredag.runtime;

import com.example.featuredag.physical.ExecutionEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单次执行的上下文（运行时）：承载单请求、离线行批或在线分组批输入，
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
    private final List<String> onlineGroupExecutionIds;
    private final List<Map<String, Object>> onlineSharedGroups;
    private final int[] candidateGroupOffsets;
    private final int[] candidateGroupIndexes;
    private final boolean onlineBatch;
    private final Map<String, ValueHandle> resultSlots = new LinkedHashMap<>();
    private final RuntimeCache runtimeCache = new RuntimeCache();
    private final Map<String, RuntimeNodeState> nodeStates = new LinkedHashMap<>();

    private ExecutionContext(
            String executionId,
            ExecutionEnvironment environment,
            Map<String, Object> sharedSourceValues,
            List<Map<String, Object>> candidates,
            List<Map<String, Object>> offlineRows,
            boolean offlineBatch,
            List<String> onlineGroupExecutionIds,
            List<Map<String, Object>> onlineSharedGroups,
            int[] candidateGroupOffsets,
            boolean onlineBatch) {
        // 输入容器在请求入口复制并冻结，确保执行期间看到稳定快照；结果槽、缓存和状态则仅在本上下文内可变。
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
        this.onlineGroupExecutionIds = List.copyOf(onlineGroupExecutionIds);
        this.onlineSharedGroups = onlineSharedGroups.stream()
                .map(group -> Collections.unmodifiableMap(new LinkedHashMap<>(group)))
                .toList();
        this.candidateGroupOffsets = candidateGroupOffsets.clone();
        this.onlineBatch = onlineBatch;
        validateOnlineBatchLayout();
        this.candidateGroupIndexes = buildCandidateGroupIndexes();
    }

    public static ExecutionContext offlineRow(String executionId, Map<String, Object> rowValues) {
        // 离线单行沿用共享源值容器，但不启用批标记，因此算子无批句柄输入时走 Single Kernel。
        return new ExecutionContext(
                executionId, ExecutionEnvironment.OFFLINE,
                rowValues, List.of(), List.of(), false,
                List.of(), List.of(), new int[] {0}, false);
    }

    public static ExecutionContext offlineBatch(
            String executionId,
            List<Map<String, Object>> rows) {
        Objects.requireNonNull(rows, "rows");
        // 离线整批以行列表为唯一变化维度，SOURCE_BINDING 会产出 OfflineBatchValue。
        return new ExecutionContext(
                executionId, ExecutionEnvironment.OFFLINE,
                Map.of(), List.of(), rows, true,
                List.of(), List.of(), new int[] {0}, false);
    }

    public static ExecutionContext onlineRequest(
            String requestId,
            Map<String, Object> userAndSceneValues,
            List<Map<String, Object>> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        // 单个在线请求也保存一组 offsets，使专用执行器可复用与在线分组批一致的候选定位语义。
        return new ExecutionContext(
                requestId, ExecutionEnvironment.ONLINE,
                userAndSceneValues, candidates, List.of(), false,
                List.of(requestId), List.of(userAndSceneValues),
                new int[] {0, candidates.size()}, false);
    }

    public static ExecutionContext onlineBatch(
            String batchExecutionId,
            List<String> groupExecutionIds,
            List<Map<String, Object>> sharedGroups,
            List<List<Map<String, Object>>> candidateGroups) {
        Objects.requireNonNull(groupExecutionIds, "groupExecutionIds");
        Objects.requireNonNull(sharedGroups, "sharedGroups");
        Objects.requireNonNull(candidateGroups, "candidateGroups");
        if (groupExecutionIds.size() != sharedGroups.size()
                || groupExecutionIds.size() != candidateGroups.size()) {
            throw new IllegalArgumentException(
                    "Online batch group ids, shared groups and candidate groups must have equal size");
        }
        List<Map<String, Object>> flattenedCandidates = new ArrayList<>();
        int[] offsets = new int[groupExecutionIds.size() + 1];
        // 在线分组批先展平成连续候选域；offsets 保留组边界，供共享值广播、报错定位和结果还原。
        for (int groupIndex = 0; groupIndex < candidateGroups.size(); groupIndex++) {
            List<Map<String, Object>> group = Objects.requireNonNull(
                    candidateGroups.get(groupIndex), "candidate group " + groupIndex);
            flattenedCandidates.addAll(group);
            offsets[groupIndex + 1] = flattenedCandidates.size();
        }
        return new ExecutionContext(
                batchExecutionId, ExecutionEnvironment.ONLINE,
                Map.of(), flattenedCandidates, List.of(), false,
                groupExecutionIds, sharedGroups, offsets, true);
    }

    public String executionId() { return executionId; }
    public ExecutionEnvironment environment() { return environment; }
    public Map<String, Object> sharedSourceValues() { return sharedSourceValues; }
    public List<Map<String, Object>> candidates() { return candidates; }
    public int candidateCount() { return candidates.size(); }
    public List<Map<String, Object>> offlineRows() { return offlineRows; }
    public boolean isOfflineBatch() { return offlineBatch; }
    public int offlineBatchSize() { return offlineRows.size(); }
    public boolean isOnlineBatch() { return onlineBatch; }
    public int onlineGroupCount() { return onlineGroupExecutionIds.size(); }
    public String onlineGroupExecutionId(int groupIndex) {
        return onlineGroupExecutionIds.get(groupIndex);
    }
    public List<Map<String, Object>> onlineSharedGroups() { return onlineSharedGroups; }
    public int candidateGroupStart(int groupIndex) { return candidateGroupOffsets[groupIndex]; }
    public int candidateGroupEnd(int groupIndex) { return candidateGroupOffsets[groupIndex + 1]; }
    public int candidateGroupIndex(int candidateIndex) {
        return candidateGroupIndexes[candidateIndex];
    }
    public int candidateIndexInGroup(int candidateIndex) {
        int groupIndex = candidateGroupIndex(candidateIndex);
        return candidateIndex - candidateGroupOffsets[groupIndex];
    }
    public Map<String, ValueHandle> resultSlots() { return resultSlots; }
    public RuntimeCache runtimeCache() { return runtimeCache; }
    /**
     * @deprecated 仅为源兼容保留。直接访问不会生成缓存统计，核心执行器应使用 runtimeCache()。
     */
    @Deprecated(forRemoval = false)
    public Map<Object, Object> cacheRegistry() { return runtimeCache.valuesView(); }
    public Map<String, RuntimeNodeState> nodeStates() { return nodeStates; }

    public RuntimeNodeState state(String physicalNodeId) {
        return nodeStates.computeIfAbsent(physicalNodeId, RuntimeNodeState::new);
    }

    private void validateOnlineBatchLayout() {
        // offsets 必须覆盖 [0, candidateCount] 且单调不减；相等的相邻值合法，表示该组没有候选。
        if (onlineGroupExecutionIds.size() != onlineSharedGroups.size()) {
            throw new IllegalArgumentException(
                    "Online batch group ids and shared groups must have equal size");
        }
        if (candidateGroupOffsets.length != onlineGroupExecutionIds.size() + 1) {
            throw new IllegalArgumentException("Invalid online batch candidate offsets");
        }
        if (candidateGroupOffsets[0] != 0
                || candidateGroupOffsets[candidateGroupOffsets.length - 1] != candidates.size()) {
            throw new IllegalArgumentException("Online batch candidate offsets do not cover candidates");
        }
        for (int index = 1; index < candidateGroupOffsets.length; index++) {
            if (candidateGroupOffsets[index] < candidateGroupOffsets[index - 1]) {
                throw new IllegalArgumentException("Online batch candidate offsets must be ordered");
            }
        }
    }

    private int[] buildCandidateGroupIndexes() {
        int[] indexes = new int[candidates.size()];
        // 预建 candidateIndex → groupIndex 的反向索引，避免运行时每次按 offsets 线性查找。
        for (int groupIndex = 0; groupIndex + 1 < candidateGroupOffsets.length; groupIndex++) {
            Arrays.fill(
                    indexes,
                    candidateGroupOffsets[groupIndex],
                    candidateGroupOffsets[groupIndex + 1],
                    groupIndex);
        }
        return indexes;
    }

}
