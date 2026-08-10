package com.example.featuredag.runtime;

import com.example.featuredag.operator.SequenceKeyDomain;
import com.example.featuredag.physical.PhysicalNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** “按 key 过滤序列后计数”的通用融合执行器。 */
public final class SequenceKeyCountExecutor implements PhysicalExecutor {
    private record SequenceIndexCacheKey(
            SequenceKeyDomain keyDomain,
            SequenceValue sequence) {}

    private record SequenceKeyCountCacheKey(
            SequenceKeyDomain keyDomain,
            SequenceValue sequence,
            Object key) {}

    private final SequenceIndexRegistry indexRegistry;

    public SequenceKeyCountExecutor(SequenceIndexRegistry indexRegistry) {
        this.indexRegistry = Objects.requireNonNull(indexRegistry, "indexRegistry");
    }

    @Override
    public void validate(PhysicalNode node) {
        indexRegistry.require(keyDomain(node));
    }

    @Override
    public ValueHandle execute(
            PhysicalNode node,
            ExecutionContext context,
            RuntimeNodeState state) {
        if (node.inputSlots().size() != 2) {
            throw new IllegalStateException("sequence-key-count requires sequence and key inputs");
        }
        Object sequenceRaw = requireSlot(context, node.inputSlots().get(0)).raw();
        if (!(sequenceRaw instanceof SequenceValue sequence)) {
            throw new IllegalArgumentException("First input must be SequenceValue");
        }

        SequenceKeyDomain keyDomain = keyDomain(node);
        SequenceIndexProvider provider = indexRegistry.require(keyDomain);
        List<Object> rawKeys = toCandidateValues(
                requireSlot(context, node.inputSlots().get(1)), context.candidateCount());
        List<Object> normalizedKeys = rawKeys.stream()
                .map(provider::normalizeQueryKey)
                .toList();

        Set<Object> uniqueKeys = new LinkedHashSet<>(normalizedKeys);
        state.setDedupCounts(normalizedKeys.size(), uniqueKeys.size());

        SequenceIndexCacheKey indexCacheKey = new SequenceIndexCacheKey(keyDomain, sequence);
        IndexValue index;
        Object cachedIndex = context.cacheRegistry().get(indexCacheKey);
        if (cachedIndex instanceof IndexValue cached) {
            index = cached;
            state.markCacheHit("REQUEST_INDEX");
        } else {
            index = provider.build(sequence);
            context.cacheRegistry().put(indexCacheKey, index);
        }

        Map<Object, Integer> countsByKey = new LinkedHashMap<>();
        for (Object key : uniqueKeys) {
            SequenceKeyCountCacheKey countCacheKey =
                    new SequenceKeyCountCacheKey(keyDomain, sequence, key);
            if (context.cacheRegistry().containsKey(countCacheKey)) {
                countsByKey.put(key, (Integer) context.cacheRegistry().get(countCacheKey));
                state.markCacheHit("CANDIDATE_KEY");
            } else {
                int count = index.count(key);
                context.cacheRegistry().put(countCacheKey, count);
                countsByKey.put(key, count);
            }
        }

        List<Object> result = new ArrayList<>(normalizedKeys.size());
        for (Object key : normalizedKeys) result.add(countsByKey.get(key));
        return new CandidateVectorValue(result);
    }

    private static ValueHandle requireSlot(ExecutionContext context, String slot) {
        ValueHandle value = context.resultSlots().get(slot);
        if (value == null) throw new IllegalStateException("Input slot not available: " + slot);
        return value;
    }

    private static SequenceKeyDomain keyDomain(PhysicalNode node) {
        Object value = node.executorConfig().get("keyDomain");
        if (value == null) {
            throw new IllegalArgumentException("sequence-key-count requires keyDomain config");
        }
        return new SequenceKeyDomain(String.valueOf(value));
    }

    private static List<Object> toCandidateValues(ValueHandle handle, int candidateCount) {
        if (handle instanceof CandidateVectorValue vector) return vector.values();
        List<Object> result = new ArrayList<>(candidateCount);
        for (int index = 0; index < candidateCount; index++) result.add(handle.raw());
        return result;
    }
}
