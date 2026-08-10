package com.example.featuredag.runtime;

/** 公共 generate 调用发生失败时所处的阶段。 */
public enum ExecutionPhase {
    NONE,
    VALIDATION,
    DECODE,
    RUNTIME,
    ENCODE
}
