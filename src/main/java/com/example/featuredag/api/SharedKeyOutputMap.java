package com.example.featuredag.api;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * 共享键布局的输出 Map（C9）：键数组属于 {@link FeatureOutputLayout} 并在所有行之间共享，
 * 本对象只持有这一行的值数组。对外是不可修改的 Map，语义与逐行 LinkedHashMap 一致：
 * 迭代顺序为键首次出现的顺序，equals/hashCode 遵循 Map 契约。
 */
final class SharedKeyOutputMap extends AbstractMap<String, List<?>> {
    private final FeatureOutputLayout layout;
    private final List<?>[] values;
    private Set<Map.Entry<String, List<?>>> entrySet;
    private Set<String> keySet;
    private Collection<List<?>> valuesView;

    SharedKeyOutputMap(FeatureOutputLayout layout, List<?>[] values) {
        this.layout = layout;
        this.values = values;
    }

    @Override
    public int size() {
        return values.length;
    }

    @Override
    public boolean isEmpty() {
        return values.length == 0;
    }

    @Override
    public boolean containsKey(Object key) {
        return layout.indexOf(key) != null;
    }

    @Override
    public boolean containsValue(Object value) {
        for (List<?> element : values) {
            if (Objects.equals(value, element)) return true;
        }
        return false;
    }

    @Override
    public List<?> get(Object key) {
        Integer slot = layout.indexOf(key);
        return slot == null ? null : values[slot];
    }

    @Override
    public Set<Map.Entry<String, List<?>>> entrySet() {
        Set<Map.Entry<String, List<?>>> set = entrySet;
        return set != null ? set : (entrySet = new EntrySet());
    }

    @Override
    public Set<String> keySet() {
        Set<String> set = keySet;
        return set != null ? set : (keySet = Collections.unmodifiableSet(super.keySet()));
    }

    @Override
    public Collection<List<?>> values() {
        Collection<List<?>> view = valuesView;
        return view != null ? view : (valuesView = Collections.unmodifiableCollection(super.values()));
    }

    // 与 Collections.unmodifiableMap 一致：所有修改方法无条件抛出，不因键是否存在而静默返回。
    @Override
    public void putAll(Map<? extends String, ? extends List<?>> map) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<?> remove(Object key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void replaceAll(java.util.function.BiFunction<? super String, ? super List<?>, ? extends List<?>> function) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<?> putIfAbsent(String key, List<?> value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(Object key, Object value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean replace(String key, List<?> oldValue, List<?> newValue) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<?> replace(String key, List<?> value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<?> computeIfAbsent(String key, java.util.function.Function<? super String, ? extends List<?>> mappingFunction) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<?> computeIfPresent(String key, java.util.function.BiFunction<? super String, ? super List<?>, ? extends List<?>> remappingFunction) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<?> compute(String key, java.util.function.BiFunction<? super String, ? super List<?>, ? extends List<?>> remappingFunction) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<?> merge(String key, List<?> value, java.util.function.BiFunction<? super List<?>, ? super List<?>, ? extends List<?>> remappingFunction) {
        throw new UnsupportedOperationException();
    }

    private final class EntrySet extends AbstractSet<Map.Entry<String, List<?>>> {
        @Override
        public int size() {
            return values.length;
        }

        @Override
        public Iterator<Map.Entry<String, List<?>>> iterator() {
            return new Iterator<Map.Entry<String, List<?>>>() {
                private int cursor;

                @Override
                public boolean hasNext() {
                    return cursor < values.length;
                }

                @Override
                public Map.Entry<String, List<?>> next() {
                    if (cursor >= values.length) throw new NoSuchElementException();
                    return new SimpleImmutableEntry<>(layout.key(cursor), values[cursor++]);
                }
            };
        }

        @Override
        public boolean contains(Object object) {
            return object instanceof Map.Entry<?, ?> entry
                    && containsKey(entry.getKey())
                    && Objects.equals(get(entry.getKey()), entry.getValue());
        }

        @Override
        public boolean remove(Object object) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeIf(java.util.function.Predicate<? super Map.Entry<String, List<?>>> filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeAll(java.util.Collection<?> collection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean retainAll(java.util.Collection<?> collection) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException();
        }
    }
}
