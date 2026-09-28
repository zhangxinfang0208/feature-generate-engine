package com.example.featuredag.operator;

/**
 * 显式选择可借用参数容器的 Single Kernel；不会改变 SCALAR_ADAPTER 路由。
 *
 * <p>evaluate 收到的列表只在本次同步调用期间有效，且可能只读。实现不得修改、
 * 保存或返回该列表，也不得让其迭代器、subList、闭包等派生引用逃逸。
 * 若需要保留容器，必须在调用返回前复制；参数元素本身的生命周期不受影响。
 * Batch 适配器可在下一行覆盖容器，实例仍须无请求状态且支持并发调用。
 * 普通 SingleOperatorKernel 不承担此约束，仍接收每行独立的可变列表。
 */
public interface BorrowedArgumentsKernel extends SingleOperatorKernel {
}
