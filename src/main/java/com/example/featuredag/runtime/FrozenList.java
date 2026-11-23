package com.example.featuredag.runtime;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;

/**
 * 引擎已冻结的只读列表：由解码器或引擎在确认底层输入稳定后创建。
 * {@link #wrap} 不复制元素，调用方须保证底层列表在请求期间不被修改；
 * {@link #copyOf} 独占自己的元素数组。两种实例都不可修改，
 * 值句柄据此识别无需再做防御复制的列表；输出边界只放行 {@link #isDetached()} 的实例。
 */
public final class FrozenList<E> extends AbstractList<E> implements RandomAccess {
    private final List<? extends E> values;
    private final boolean detached;

    private FrozenList(List<? extends E> values, boolean detached) {
        this.values = values;
        this.detached = detached;
    }

    /** 只读视图：不复制元素，底层列表保持调用方所有；已是冻结列表时原样返回。 */
    @SuppressWarnings("unchecked")
    public static <E> FrozenList<E> wrap(List<? extends E> values) {
        Objects.requireNonNull(values, "values");
        if (values instanceof FrozenList<?> frozen) return (FrozenList<E>) frozen;
        // 本类声明 RandomAccess；链表等按序访问的输入先转成数组列表，避免按下标扫描退化为平方复杂度。
        List<? extends E> backing = values instanceof RandomAccess ? values : new ArrayList<>(values);
        return new FrozenList<>(backing, false);
    }

    /** 防御复制：复制一份元素后冻结，调用方之后修改原列表不影响本列表。 */
    public static <E> FrozenList<E> copyOf(List<? extends E> values) {
        return new FrozenList<>(new ArrayList<>(values), true);
    }

    /**
     * 元素数组是否由引擎独占、不引用调用方的容器。
     * 借用输入得到的视图在请求返回后可能被调用方复用，因此不能直接出现在公共输出里。
     */
    public boolean isDetached() {
        return detached;
    }

    @Override
    public E get(int index) {
        return values.get(index);
    }

    @Override
    public int size() {
        return values.size();
    }
}
