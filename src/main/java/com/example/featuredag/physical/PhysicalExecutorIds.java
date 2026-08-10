package com.example.featuredag.physical;

/** 物理执行器的稳定注册标识；专用执行器通过注册表解析，核心运行时不感知业务名称。 */
public final class PhysicalExecutorIds {
    public static final String SOURCE_BINDING = "source-binding";
    public static final String LITERAL = "literal";
    public static final String GENERIC_OPERATOR = "generic-operator";
    public static final String FEATURE_OUTPUT = "feature-output";
    public static final String SEQUENCE_KEY_COUNT = "sequence-key-count";

    private PhysicalExecutorIds() {}
}
