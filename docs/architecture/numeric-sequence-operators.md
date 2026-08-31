# 数值算子序列逐元素语义

`min`、`max`、`add`、`sub`、`mul`、`div` 同时支持数值标量和数值序列。全标量调用保持原有行为；任一输入的 `ValueShape` 为 `SEQUENCE` 时，输出固定为 `SEQUENCE`，实体域仍取全部输入的并集。

## 广播与对齐

- 标量向序列的每个位置广播，例如 `add([1, 2], 10) = [11, 12]`。
- 多个序列按相同逻辑下标逐元素计算，例如 `max([3, 1], [2, 4]) = [3, 4]`。
- 多个序列必须等长；长度不同时求值失败，不做截断、补齐或循环广播。
- 空序列与标量运算返回空序列；多个序列同时出现时仍须全部为空。
- `min`、`max` 继续要求至少两个参数，`min(sequence)` 不表示序列聚合。
- `EVENT_SEQUENCE` 不做隐式数值字段投影，构图期直接拒绝。

每个位置沿用原标量 Kernel 的数值规则，包括精确十进制计算、类型载体、溢出检查和 `div` 分母为零返回 `0.0`。某个元素失败时，异常包含对应的序列下标。

## 推断与执行

推断阶段按 `DOUBLE > BIGINT > INT` 计算元素类型（`div` 固定为 `DOUBLE`），并按是否存在序列输入决定输出 shape。算子可直接消费 `OperatorSequence`，避免为了逐元素计算预先物化序列视图。

六个算子不提供原生 `BatchOperatorKernel`。Batch 继续由 `SingleLoopBatchOperatorKernel` 逐行调用 Single Kernel；如果某一批行的输入本身是序列，则在该行内部执行上述逐元素逻辑。这样保持批行数量与顺序不变，也不把逻辑序列维度误当成 Batch 行维度。

## `discrete` 与 `log_base`

- `discrete(value, boundaries)` 只允许 `value` 在标量和序列之间变化；`boundaries` 始终是整条 value 序列共享的有序边界。例如 `discrete([5, 15, 25], [10, 20]) = [0, 1, 2]`。
- `log_base(value, base, upbound)` 允许 `value` 和 `base` 为标量或序列；一个为标量时广播，两个都是序列时必须等长。`upbound` 始终是共享标量。例如 `log_base([1, 8, 128], 2, 64) = [0.0, 3.0, 6.0]`。
- 两个算子都能直接消费 `OperatorSequence`；value/base 元素求值失败时，异常包含对应的逻辑序列下标。
- 两个算子仍不提供原生 `BatchOperatorKernel`，继续通过 `SingleLoopBatchOperatorKernel` 保持逐行等价。
