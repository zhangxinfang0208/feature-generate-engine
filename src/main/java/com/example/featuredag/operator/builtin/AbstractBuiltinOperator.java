package com.example.featuredag.operator.builtin;

import com.example.featuredag.operator.OperatorDefinition;
import com.example.featuredag.operator.OperatorSemantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 首期内置算子的不可变元数据基类。
 * 注册类只负责装配具体实例；参数范围、确定性、序列视图能力和成本均由各算子构造器显式声明。
 */
public abstract class AbstractBuiltinOperator implements OperatorDefinition {
    private final String name;
    private final int minArguments;
    private final int maxArguments;
    private final boolean deterministic;
    private final boolean supportsSequenceView;
    private final List<OperatorSemantic> semantics;

    protected AbstractBuiltinOperator(
            String name,
            int minArguments,
            int maxArguments,
            boolean deterministic,
            boolean supportsSequenceView) {
        this(name, minArguments, maxArguments, deterministic, supportsSequenceView,
                Collections.<OperatorSemantic>emptyList());
    }

    protected AbstractBuiltinOperator(
            String name,
            int minArguments,
            int maxArguments,
            boolean deterministic,
            boolean supportsSequenceView,
            List<OperatorSemantic> semantics) {
        this.name = Objects.requireNonNull(name, "name");
        // 在算子实例创建时一次性校验元数据，避免非法定义进入全局注册表。
        if (name.trim().isEmpty()) throw new IllegalArgumentException("name must not be blank");
        if (minArguments < 0 || maxArguments < minArguments) {
            throw new IllegalArgumentException("invalid argument range for operator " + name);
        }
        this.minArguments = minArguments;
        this.maxArguments = maxArguments;
        this.deterministic = deterministic;
        this.supportsSequenceView = supportsSequenceView;
        this.semantics = Collections.unmodifiableList(new ArrayList<OperatorSemantic>(semantics));
    }

    @Override public final String name() { return name; }
    @Override public final int minArguments() { return minArguments; }
    @Override public final int maxArguments() { return maxArguments; }
    @Override public final boolean deterministic() { return deterministic; }
    @Override public final boolean supportsSequenceView() { return supportsSequenceView; }
    @Override public final boolean sideEffectFree() { return true; }
    @Override public final List<OperatorSemantic> semantics() { return semantics; }
}
