package com.example.featuredag.operator;

/** 标准序列字段语义标识。新增字段时在运行时注册对应索引 Provider。 */
public final class SequenceKeyDomains {
    public static final SequenceKeyDomain INDUSTRY = new SequenceKeyDomain("event.industry");

    private SequenceKeyDomains() {}
}
