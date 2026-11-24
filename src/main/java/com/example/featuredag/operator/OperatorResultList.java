package com.example.featuredag.operator;

import java.util.AbstractList;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;

/**
 * 算子返回的独占只读列表：包装算子内部刚构建、之后不再修改的列表，不做防御复制。
 *
 * <p>调用方通过 {@link #wrap} 交出该列表的所有权：之后不得再保存可变引用或修改它。
 * 本类型不可修改；运行时在值句柄和输出边界识别到它（且元素均为标量）时直接透传，
 * 不再为同一份结果反复复制。</p>
 */
public final class OperatorResultList<E> extends AbstractList<E> implements RandomAccess {
    private final List<? extends E> values;

    private OperatorResultList(List<? extends E> values) {
        this.values = values;
    }

    /** 包装一个算子独占的列表；调用方之后不得再修改该列表。 */
    public static <E> OperatorResultList<E> wrap(List<? extends E> values) {
        return new OperatorResultList<>(Objects.requireNonNull(values, "values"));
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
