# `append` 与 `join` 算子

## `append`

`append(left, right)` 固定接收两个参数，并始终返回 `SEQUENCE`。参数顺序即输出顺序：

| 输入组合 | 结果示例 |
| --- | --- |
| 序列 + 序列 | `append(["a", "b"], ["c"])` → `["a", "b", "c"]` |
| 序列 + 标量 | `append(["a", "b"], "c")` → `["a", "b", "c"]` |
| 标量 + 序列 | `append("a", ["b", "c"])` → `["a", "b", "c"]` |
| 标量 + 标量 | `append("a", "b")` → `["a", "b"]` |

两侧元素类型必须相同，数值类型允许沿 `INT → BIGINT → DOUBLE` 安全提升；`UNKNOWN`
（例如 `null` 字面量）采用另一侧的已知类型。`OBJECT`、`EVENT_SEQUENCE` 以及普通值与数值的
混合输入会在构图期被拒绝，直接调用注册表时也会进行元素级防御校验。结果列表不可变。

## `join`

`join(sequence, delimiter?)` 把非结构化序列折叠为 `STRING/SCALAR`：

- 未提供 `delimiter` 时默认使用 `#`；
- 分隔符必须是 `STRING/SCALAR`，可以是空字符串；
- 空序列返回空字符串，单元素序列不添加分隔符；
- 普通 `null` 元素按字符串 `null` 处理；
- `OBJECT` 与 `EVENT_SEQUENCE` 元素在构图期和运行期均被拒绝。

例如：

```text
join(["a", "b"], "|") = "a|b"
join([1, 2, 3]) = "1#2#3"
join([], ",") = ""
```

两个算子都声明 `supportsSequenceView=true`，可直接消费 `OperatorSequence`；它们不提供原生
`BatchOperatorKernel`，Batch 执行由 `SingleLoopBatchOperatorKernel` 逐行适配并保持行数与顺序。
