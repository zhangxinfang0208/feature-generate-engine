package com.example.featuredag.operator;

import java.util.Objects;

/** 序列字段的稳定语义标识；物理索引实现通过该标识在运行时注册表中解析。 */
public record SequenceKeyDomain(String value) {
    public SequenceKeyDomain {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("value must not be blank");
    }
}
