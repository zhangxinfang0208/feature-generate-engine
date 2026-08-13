package com.example.featuredag.runtime;

import com.example.featuredag.operator.SequenceKeyDomain;
import com.example.featuredag.operator.SequenceKeyDomains;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** 序列索引 Provider 注册表；相同 keyDomain 只能注册一次。 */
public final class SequenceIndexRegistry {
    private final Map<SequenceKeyDomain, SequenceIndexProvider> providers = new LinkedHashMap<>();

    public SequenceIndexRegistry register(SequenceIndexProvider provider) {
        Objects.requireNonNull(provider, "provider");
        SequenceIndexProvider previous = providers.putIfAbsent(provider.keyDomain(), provider);
        if (previous != null) {
            throw new IllegalArgumentException(
                    "Sequence index provider already registered: " + provider.keyDomain().value());
        }
        return this;
    }

    public SequenceIndexRegistry register(
            SequenceKeyDomain keyDomain,
            SequenceKeyExtractor extractor,
            UnaryOperator<Object> queryKeyNormalizer) {
        Objects.requireNonNull(keyDomain, "keyDomain");
        Objects.requireNonNull(extractor, "extractor");
        Objects.requireNonNull(queryKeyNormalizer, "queryKeyNormalizer");
        return register(new SequenceIndexProvider() {
            @Override public SequenceKeyDomain keyDomain() { return keyDomain; }
            @Override public SequenceKeyExtractor keyExtractor() { return extractor; }
            @Override public Object normalizeQueryKey(Object key) { return queryKeyNormalizer.apply(key); }
        });
    }

    public SequenceIndexProvider require(SequenceKeyDomain keyDomain) {
        SequenceIndexProvider provider = providers.get(keyDomain);
        if (provider == null) {
            throw new IllegalArgumentException(
                    "No sequence index provider registered for key domain: " + keyDomain.value());
        }
        return provider;
    }

    public boolean contains(SequenceKeyDomain keyDomain) {
        return providers.containsKey(keyDomain);
    }

    public static SequenceIndexRegistry standard() {
        return new SequenceIndexRegistry().register(
                SequenceKeyDomains.INDUSTRY,
                (block, index) -> block.columnValueAt("industryId", index),
                key -> String.valueOf(key));
    }
}
