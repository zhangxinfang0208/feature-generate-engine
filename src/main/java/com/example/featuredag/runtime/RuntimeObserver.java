package com.example.featuredag.runtime;

/**
 * 可插拔的执行观测出口；核心运行时不依赖日志或具体指标框架。
 * 同一个引擎实例可被并发调用，生产实现必须线程安全并避免阻塞执行线程。
 */
@FunctionalInterface
public interface RuntimeObserver {
    RuntimeObserver NOOP = diagnostics -> {};

    void onExecutionCompleted(ExecutionDiagnostics diagnostics);

    static RuntimeObserver noop() {
        return NOOP;
    }
}
