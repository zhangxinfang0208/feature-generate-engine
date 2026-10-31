# 序列比较、连接、相邻差与均值

五个算子均在 `OperatorRegistry.standard()` 中注册，支持基础类型 `List` 和
`OperatorSequence`（包括筛选、重排后的序列视图）。下标和相邻关系均按视图的逻辑顺序计算。
算子不修改输入，序列输出是独立的不可变列表。

```text
time_seq = [1, 3, 5, 7, 9, 12, 19]
compare_smaller(time_seq, 8) = [0, 1, 2, 3]
compare_greater(time_seq, 8) = [4, 5, 6]
concat_seq(['a', 'b', 'c'], ['d', 'e', 'f', 'a']) = ['a', 'b', 'c', 'd', 'e', 'f', 'a']
calc_seq_sub(time_seq) = [2, 2, 2, 2, 3, 7]
mean(calc_seq_sub(time_seq)) = 3.0
```

## 参数、类型与边界

| 算子 | 输入 | 输出与空序列行为 |
| --- | --- | --- |
| `compare_smaller(sequence, value)` | 数值序列、数值标量阈值 | 严格小于阈值的全部零基 INT 下标；空序列返回 `[]` |
| `compare_greater(sequence, value)` | 数值序列、数值标量阈值 | 严格大于阈值的全部零基 INT 下标；空序列返回 `[]` |
| `concat_seq(left, right)` | 两个兼容的基础类型序列 | 左序列后接右序列；空序列为连接的单位元 |
| `calc_seq_sub(sequence)` | 一个数值序列 | `sequence[i+1] - sequence[i]`；空序列或合法单元素序列返回 `[]` |
| `mean(sequence)` | 一个非空数值序列 | DOUBLE 标量；空序列抛出异常，可在衍生特征配置 `dft` 回退 |

比较不要求序列有序，不去重，相等元素不命中。使用精确十进制比较，避免 BIGINT
转 double 后丢失下标匹配精度。即使序列为空，阈值仍须是有效的有限数值。

`concat_seq` 保留顺序、重复项、null 和原始元素载体。支持数值、STRING、BOOLEAN；
两个序列须同型或均为数值，数值输出类型按 `INT → BIGINT → DOUBLE` 提升。
它只接受两个序列，不展开嵌套序列，也不将字符串拼接成一个标量。

`calc_seq_sub` 按输入元素类型推断输出类型；求值沿用 `sub` 的规则：相邻两个元素都是
整型载体时返回 Long，否则返回 Double。降序产生负差，相等产生零。
整数差超出 long 范围、浮点差超出 double 范围均报错，不回绕或返回 Infinity。
`mean` 使用精确十进制累加，再以 DECIMAL128 精度求商并转为 Double，避免整数除法截断
及中间累加溢出（例如两个 `Double.MAX_VALUE` 的均值仍为 `Double.MAX_VALUE`）。
数值算子拒绝 null、非数值及 NaN/Infinity 元素，并报告出错逻辑下标。

所有算子拒绝非序列输入与 `EVENT_SEQUENCE`，不做隐式事件字段投影。
序列字面量的元数据是 OBJECT，因此先允许构图，再在运行时验证元素类型；
`concat_seq` 和 `calc_seq_sub` 对未知元素类型输出 UNKNOWN，由衍生特征声明确定边界类型。
支持匿名嵌套及 `INTERNAL_ONLY` 具名中间态，例如：

```text
slice_by_indices(time_seq, compare_smaller(time_seq, 8))
mean(calc_seq_sub(time_seq))
compare_greater(sequence=time_seq, value=8)
```

## 执行与架构

仅扩展 `operator.builtin` 实现和标准注册清单，未改变规划或运行时分层。
输出实体域为所有参数实体域的并集。五个算子均确定、无副作用，不持有请求状态。
目前没有批内重复输入的基准收益数据，因此均使用 `SCALAR_ADAPTER`，由
`SingleLoopBatchOperatorKernel` 保证 Single/Batch 逐行等价、行序和既有失败回退语义。
