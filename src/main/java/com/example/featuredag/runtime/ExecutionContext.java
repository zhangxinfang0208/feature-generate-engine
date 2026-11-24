package com.example.featuredag.runtime;

import com.example.featuredag.physical.ExecutionEnvironment;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
    private final ColumnarCandidateValues candidateColumns;
    private final List<Map<String, Object>> offlineRows;
    private final boolean offlineBatch;
    private final List<String> onlineGroupExecutionIds;
    private final List<Map<String, Object>> onlineSharedGroups;
    private final int[] candidateGroupOffsets;
    private final int[] candidateGroupIndexes;
    private final boolean onlineBatch;
    // —— 结果槽与节点状态（C9）——
    // 公开视图始终有效：绑定计划前直接读写后备 Map；绑定后以序号数组为准，
    // 核心运行时与外部（扩展执行器、诊断、测试）的写入落到同一份数据，不需要物化或镜像。
    private final Map<String, ValueHandle> slotBacking = new LinkedHashMap<>();
    private final SlotTable resultSlots = new SlotTable();
    private final RuntimeCache runtimeCache = new RuntimeCache();
    private final Map<String, RuntimeNodeState> nodeStateBacking = new LinkedHashMap<>();
    private final NodeStateTable nodeStates = new NodeStateTable();
    private RuntimePlanLayout layout;
    private ValueHandle[] slotValues = new ValueHandle[0];
    private RuntimeNodeState[] nodeStateValues = new RuntimeNodeState[0];
    private static final Object NO_BATCH_FAILURE = new Object();
    // 只在当前请求存活，以 identity 为键，避免 record.hashCode 遍历整个向量。
    private IdentityHashMap<ValueHandle, Object> batchFailureChecks;

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
        this(executionId, environment, sharedSourceValues, candidates, offlineRows, offlineBatch,
                onlineGroupExecutionIds, onlineSharedGroups, candidateGroupOffsets, onlineBatch, false);
    }

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
            boolean onlineBatch,
            boolean ownedDecodedInputs) {
        this(executionId, environment, sharedSourceValues, candidates, offlineRows, offlineBatch,
                onlineGroupExecutionIds, onlineSharedGroups, candidateGroupOffsets, onlineBatch,
                ownedDecodedInputs, null);
    }

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
            boolean onlineBatch,
            boolean ownedDecodedInputs,
            ColumnarCandidateValues candidateColumns) {
        // 输入容器在请求入口复制并冻结，确保执行期间看到稳定快照；结果槽、缓存和状态则仅在本上下文内可变。
        this.executionId = Objects.requireNonNull(executionId, "executionId");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.sharedSourceValues = freezeInputMap(sharedSourceValues, ownedDecodedInputs);
        this.candidateColumns = candidateColumns;
        this.candidates = candidateColumns != null ? candidateColumns : candidates.stream()
                .map(candidate -> freezeInputMap(candidate, ownedDecodedInputs))
                .toList();
        this.offlineRows = offlineRows.stream()
                .map(row -> freezeInputMap(row, ownedDecodedInputs))
                .toList();
        this.offlineBatch = offlineBatch;
        this.onlineGroupExecutionIds = List.copyOf(onlineGroupExecutionIds);
        this.onlineSharedGroups = onlineSharedGroups.stream()
                // 单请求的共享输入与唯一 group 是同一张 Map，只需冻结一次。
                .map(group -> group == sharedSourceValues ? this.sharedSourceValues
                        : freezeInputMap(group, ownedDecodedInputs))
                .toList();
        this.candidateGroupOffsets = candidateGroupOffsets.clone();
        this.onlineBatch = onlineBatch;
        validateOnlineBatchLayout();
        this.candidateGroupIndexes = buildCandidateGroupIndexes();
    }

    private static Map<String, Object> freezeInputMap(Map<String, Object> values, boolean owned) {
        return Collections.unmodifiableMap(owned ? values : new LinkedHashMap<>(values));
    }

    /**
     * 引擎编解码边界的所有权交接入口（C1/C10），只用于刚完成解码的独占 Map。
     * 调用方交出共享 Map 和每张候选 Map 的所有权，后续不得通过任何别名修改；
     * 嵌套值必须已由解码器完成必要的快照/转换。外层候选列表仍重新组装并冻结。
     * 普通调用方应使用保留防御复制的 onlineRequest。
     */
    public static ExecutionContext onlineRequestFromOwnedDecodedValues(
            String requestId,
            Map<String, Object> shared,
            List<Map<String, Object>> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        return new ExecutionContext(requestId, ExecutionEnvironment.ONLINE,
                shared, candidates, List.of(), false, List.of(requestId), List.of(shared),
                new int[] {0, candidates.size()}, false, true);
    }

    /**
     * C1/C10：接管解码器独占的列 Map，列及嵌套值必须在执行期间稳定且只读。
     * 不物化候选行；candidates() 保留按需行视图供已有扩展读取。
     */
    public static ExecutionContext onlineColumnarRequestFromOwnedDecodedValues(
            String requestId,
            Map<String, Object> shared,
            Map<String, List<?>> columns,
            int candidateCount) {
        return new ExecutionContext(requestId, ExecutionEnvironment.ONLINE,
                shared, List.of(), List.of(), false, List.of(requestId), List.of(shared),
                new int[] {0, candidateCount}, false, true,
                new ColumnarCandidateValues(columns, candidateCount));
    }

    /** 顶层批元素不可变；逐个值句柄只扫描一次，不能依赖算子纯度推断成功。 */
    EvaluationFailure firstBatchFailure(ValueHandle handle) {
        List<?> values;
        if (handle instanceof CandidateVectorValue vector) values = vector.values();
        else if (handle instanceof OfflineBatchValue batch) values = batch.values();
        else if (handle instanceof RequestBatchValue batch) values = batch.values();
        else if (handle instanceof CandidateBatchValue batch) values = batch.values();
        else return null;
        if (batchFailureChecks == null) batchFailureChecks = new IdentityHashMap<>();
        Object checked = batchFailureChecks.get(handle);
        if (checked != null) {
            return checked == NO_BATCH_FAILURE ? null : (EvaluationFailure) checked;
        }
        for (Object value : values) {
            if (value instanceof EvaluationFailure failure) {
                batchFailureChecks.put(handle, failure);
                return failure;
            }
        }
        batchFailureChecks.put(handle, NO_BATCH_FAILURE);
        return null;
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

    /** C1/C10：接管解码器新建的独占行 Map；调用方交接后不得修改这些 Map。 */
    public static ExecutionContext offlineBatchFromOwnedDecodedValues(
            String executionId, List<Map<String, Object>> rows) {
        Objects.requireNonNull(rows, "rows");
        return new ExecutionContext(executionId, ExecutionEnvironment.OFFLINE,
                Map.of(), List.of(), rows, true,
                List.of(), List.of(), new int[] {0}, false, true);
    }

    /** C1/C10：单行解码也使用同一所有权交接契约。 */
    public static ExecutionContext offlineRowFromOwnedDecodedValues(
            String executionId, Map<String, Object> row) {
        return new ExecutionContext(executionId, ExecutionEnvironment.OFFLINE,
                row, List.of(), List.of(), false,
                List.of(), List.of(), new int[] {0}, false, true);
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
        return onlineBatch(batchExecutionId, groupExecutionIds, sharedGroups, candidateGroups, false);
    }

    /**
     * C1/C10：接管解码器新建的独占组/候选 Map；调用方交接后不得再修改这些 Map。
     * 与单请求的 onlineRequestFromOwnedDecodedValues 同一契约：只加只读视图，不再复制。
     */
    public static ExecutionContext onlineBatchFromOwnedDecodedValues(
            String batchExecutionId,
            List<String> groupExecutionIds,
            List<Map<String, Object>> sharedGroups,
            List<List<Map<String, Object>>> candidateGroups) {
        return onlineBatch(batchExecutionId, groupExecutionIds, sharedGroups, candidateGroups, true);
    }

    private static ExecutionContext onlineBatch(
            String batchExecutionId,
            List<String> groupExecutionIds,
            List<Map<String, Object>> sharedGroups,
            List<List<Map<String, Object>>> candidateGroups,
            boolean ownedDecodedInputs) {
        Objects.requireNonNull(groupExecutionIds, "groupExecutionIds");
        Objects.requireNonNull(sharedGroups, "sharedGroups");
        Objects.requireNonNull(candidateGroups, "candidateGroups");
        if (groupExecutionIds.size() != sharedGroups.size()
                || groupExecutionIds.size() != candidateGroups.size()) {
            throw new IllegalArgumentException(
                    "Online batch group ids, shared groups and candidate groups must have equal size");
        }
        int candidateCount = 0;
        for (int groupIndex = 0; groupIndex < candidateGroups.size(); groupIndex++) {
            List<Map<String, Object>> group = candidateGroups.get(groupIndex);
            // 仅在失败分支拼接组下标，正常路径不做字符串拼接。
            if (group == null) throw new NullPointerException("candidate group " + groupIndex);
            candidateCount += group.size();
        }
        List<Map<String, Object>> flattenedCandidates = new ArrayList<>(candidateCount);
        int[] offsets = new int[groupExecutionIds.size() + 1];
        // 在线分组批先展平成连续候选域；offsets 保留组边界，供共享值广播、报错定位和结果还原。
        for (int groupIndex = 0; groupIndex < candidateGroups.size(); groupIndex++) {
            flattenedCandidates.addAll(candidateGroups.get(groupIndex));
            offsets[groupIndex + 1] = flattenedCandidates.size();
        }
        return new ExecutionContext(
                batchExecutionId, ExecutionEnvironment.ONLINE,
                Map.of(), flattenedCandidates, List.of(), false,
                groupExecutionIds, sharedGroups, offsets, true, ownedDecodedInputs);
    }

    public String executionId() { return executionId; }
    public ExecutionEnvironment environment() { return environment; }
    public Map<String, Object> sharedSourceValues() { return sharedSourceValues; }
    public List<Map<String, Object>> candidates() { return candidates; }
    public int candidateCount() { return candidates.size(); }
    boolean hasCandidateColumns() { return candidateColumns != null; }
    List<?> candidateColumn(String name) { return candidateColumns.column(name); }
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
    /** 槽位 → 值句柄 的公开视图；以 slot:N 为键，实时反映核心运行时和外部的写入。 */
    public Map<String, ValueHandle> resultSlots() { return resultSlots; }

    /**
     * 执行前绑定计划布局，此后核心运行时按序号访问槽位与节点状态。
     * 绑定前已写入 Map 的槽位和状态迁移到数组，不属于该计划的键留在后备 Map 中；
     * 之前绑定过别的计划时，先把旧计划的数组内容写回后备 Map。
     */
    void bind(RuntimePlanLayout layout) {
        if (this.layout == layout) return;
        copySlotsToBacking();
        copyNodeStatesToBacking();
        ValueHandle[] slots = new ValueHandle[layout.slotCount()];
        Iterator<Map.Entry<String, ValueHandle>> slotEntries = slotBacking.entrySet().iterator();
        while (slotEntries.hasNext()) {
            Map.Entry<String, ValueHandle> entry = slotEntries.next();
            int ordinal = layout.slotOrdinal(entry.getKey());
            if (ordinal >= 0) {
                slots[ordinal] = entry.getValue();
                slotEntries.remove();
            }
        }
        RuntimeNodeState[] states = new RuntimeNodeState[layout.nodeCount()];
        Iterator<Map.Entry<String, RuntimeNodeState>> stateEntries = nodeStateBacking.entrySet().iterator();
        while (stateEntries.hasNext()) {
            Map.Entry<String, RuntimeNodeState> entry = stateEntries.next();
            int ordinal = layout.nodeOrdinal(entry.getKey());
            if (ordinal >= 0) {
                states[ordinal] = entry.getValue();
                stateEntries.remove();
            }
        }
        this.layout = layout;
        this.slotValues = slots;
        this.nodeStateValues = states;
    }

    private void copySlotsToBacking() {
        if (layout == null) return;
        for (int ordinal = 0; ordinal < slotValues.length; ordinal++) {
            ValueHandle value = slotValues[ordinal];
            if (value != null) slotBacking.put(layout.slotName(ordinal), value);
        }
    }

    private void copyNodeStatesToBacking() {
        if (layout == null) return;
        for (int ordinal = 0; ordinal < nodeStateValues.length; ordinal++) {
            RuntimeNodeState state = nodeStateValues[ordinal];
            if (state != null) nodeStateBacking.put(layout.nodeId(ordinal), state);
        }
    }

    private int slotOrdinalOf(Object key) {
        return key instanceof String slot && layout != null ? layout.slotOrdinal(slot) : -1;
    }

    private int nodeOrdinalOf(Object key) {
        return key instanceof String nodeId && layout != null ? layout.nodeOrdinal(nodeId) : -1;
    }

    /** 按序号读取槽位；供已绑定计划的核心运行时使用。 */
    ValueHandle slotValue(int slotOrdinal) {
        return slotValues[slotOrdinal];
    }

    /** 按名称读取槽位；供专用执行器等按 slot:N 访问，不经过公开视图的迭代。 */
    ValueHandle slotByName(String slot) {
        int ordinal = slotOrdinalOf(slot);
        return ordinal >= 0 ? slotValues[ordinal] : slotBacking.get(slot);
    }

    /** 写入节点的输出槽。 */
    void putSlot(int slotOrdinal, ValueHandle value) {
        slotValues[slotOrdinal] = value;
    }

    /** 取得节点的运行状态，不存在时创建。 */
    RuntimeNodeState nodeState(int nodeOrdinal, String physicalNodeId) {
        RuntimeNodeState state = nodeStateValues[nodeOrdinal];
        if (state == null) {
            state = new RuntimeNodeState(physicalNodeId);
            nodeStateValues[nodeOrdinal] = state;
        }
        return state;
    }

    /** 同时清除槽、状态和失败检查缓存持有的引用，保留状态元数据。 */
    void releaseIntermediate(int slotOrdinal, int nodeOrdinal) {
        ValueHandle value = slotValues[slotOrdinal];
        slotValues[slotOrdinal] = null;
        RuntimeNodeState state = nodeStateValues[nodeOrdinal];
        if (state != null) state.releaseResult();
        if (batchFailureChecks != null && value != null) batchFailureChecks.remove(value);
    }

    /** 槽位表视图：计划内的槽位落在序号数组，其余键落在后备 Map；槽位不保存 null 值。 */
    private final class SlotTable extends AbstractMap<String, ValueHandle> {
        @Override
        public ValueHandle get(Object key) {
            int ordinal = slotOrdinalOf(key);
            return ordinal >= 0 ? slotValues[ordinal] : slotBacking.get(key);
        }

        @Override
        public boolean containsKey(Object key) {
            return get(key) != null;
        }

        @Override
        public ValueHandle put(String slot, ValueHandle value) {
            int ordinal = slotOrdinalOf(slot);
            ValueHandle previous = ordinal >= 0 ? slotValues[ordinal] : slotBacking.get(slot);
            if (ordinal >= 0) slotValues[ordinal] = value;
            else slotBacking.put(slot, value);
            return previous;
        }

        @Override
        public ValueHandle remove(Object key) {
            int ordinal = slotOrdinalOf(key);
            if (ordinal >= 0) {
                ValueHandle previous = slotValues[ordinal];
                slotValues[ordinal] = null;
                return previous;
            }
            return slotBacking.remove(key);
        }

        @Override
        public void clear() {
            Arrays.fill(slotValues, null);
            slotBacking.clear();
        }

        @Override
        public Set<Map.Entry<String, ValueHandle>> entrySet() {
            Map<String, ValueHandle> snapshot = new LinkedHashMap<>();
            for (int ordinal = 0; ordinal < slotValues.length; ordinal++) {
                ValueHandle value = slotValues[ordinal];
                if (value != null) snapshot.put(layout.slotName(ordinal), value);
            }
            snapshot.putAll(slotBacking);
            return Collections.unmodifiableSet(snapshot.entrySet());
        }
    }

    /** 节点状态表视图：计划内的节点落在序号数组，其余键落在后备 Map。 */
    private final class NodeStateTable extends AbstractMap<String, RuntimeNodeState> {
        @Override
        public RuntimeNodeState get(Object key) {
            int ordinal = nodeOrdinalOf(key);
            return ordinal >= 0 ? nodeStateValues[ordinal] : nodeStateBacking.get(key);
        }

        @Override
        public boolean containsKey(Object key) {
            return get(key) != null;
        }

        @Override
        public RuntimeNodeState put(String physicalNodeId, RuntimeNodeState state) {
            int ordinal = nodeOrdinalOf(physicalNodeId);
            RuntimeNodeState previous =
                    ordinal >= 0 ? nodeStateValues[ordinal] : nodeStateBacking.get(physicalNodeId);
            if (ordinal >= 0) nodeStateValues[ordinal] = state;
            else nodeStateBacking.put(physicalNodeId, state);
            return previous;
        }

        @Override
        public RuntimeNodeState remove(Object key) {
            int ordinal = nodeOrdinalOf(key);
            if (ordinal >= 0) {
                RuntimeNodeState previous = nodeStateValues[ordinal];
                nodeStateValues[ordinal] = null;
                return previous;
            }
            return nodeStateBacking.remove(key);
        }

        @Override
        public RuntimeNodeState computeIfAbsent(
                String physicalNodeId,
                java.util.function.Function<? super String, ? extends RuntimeNodeState> mappingFunction) {
            RuntimeNodeState state = get(physicalNodeId);
            if (state == null) {
                state = mappingFunction.apply(physicalNodeId);
                if (state != null) put(physicalNodeId, state);
            }
            return state;
        }

        @Override
        public void clear() {
            Arrays.fill(nodeStateValues, null);
            nodeStateBacking.clear();
        }

        @Override
        public Set<Map.Entry<String, RuntimeNodeState>> entrySet() {
            Map<String, RuntimeNodeState> snapshot = new LinkedHashMap<>();
            for (int ordinal = 0; ordinal < nodeStateValues.length; ordinal++) {
                RuntimeNodeState state = nodeStateValues[ordinal];
                if (state != null) snapshot.put(layout.nodeId(ordinal), state);
            }
            snapshot.putAll(nodeStateBacking);
            return Collections.unmodifiableSet(snapshot.entrySet());
        }
    }

    public RuntimeCache runtimeCache() { return runtimeCache; }

    /**
     * @deprecated 仅为源兼容保留。直接访问不会生成缓存统计，核心执行器应使用 runtimeCache()。
     */
    @Deprecated(forRemoval = false)
    public Map<Object, Object> cacheRegistry() { return runtimeCache.valuesView(); }

    /** 节点 → 运行状态 的公开视图；以 physical:N 为键，实时反映核心运行时和外部的写入。 */
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
