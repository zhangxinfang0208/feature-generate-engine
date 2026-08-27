# 多目标下标匹配与频次排序

## `find_indices_any`

`find_indices_any(sequence, targets)` 返回 `sequence` 中命中 `targets` 任一值的全部逻辑下标：

```text
find_indices_any(
    ["c1", "c2", "c3", "c2", "c1", "c6"],
    ["c1", "c2", "c5", "c1"]
)
=> [0, 1, 3, 4]
```

结果严格保持源序列顺序。`targets` 使用集合语义，重复目标不会重复产生下标；源序列中的重复命中会保留各自下标。空目标序列返回空结果，`null` 按普通值参与相等匹配。两个输入都必须推断为 `SEQUENCE`，输出固定为 `INT / SEQUENCE`，实体域取两个输入的并集。

该算子支持 `OperatorSequence`，但没有原生 `BatchOperatorKernel`。在没有基准数据证明批内复用收益前，由 `SingleLoopBatchOperatorKernel` 逐行适配。

## `group_count_concat` 排序配置

`group_count_concat` 的第二个对象参数支持：

| 字段 | 默认值 | 可选值 | 语义 |
| --- | --- | --- | --- |
| `delimiter` | `#` | 任意可转为字符串的值 | 值与频次之间的分隔符 |
| `order` | `FIRST_OCCURRENCE` | `FIRST_OCCURRENCE`、`COUNT_DESC` | 输出排序方式 |

`COUNT_DESC` 先按频次降序，同频时按值在输入序列中的首次出现顺序。默认值保持历史行为兼容；未知 `order` 会立即失败，不静默回退。

## 组合场景

```text
group_count_concat(
    slice_by_indices(
        user_cluster_id_seq,
        find_indices_any(user_cluster_id_seq, Item.i2i_top5_cluster_id)
    ),
    {"delimiter":"#", "order":"COUNT_DESC"}
)
```

例如用户序列为 `[c1,c2,c3,c2,c1,c2,c4,c5,c6]`，Item 目标为 `[c1,c2,c3,c4,c5]`，结果为：

```text
["c2#3", "c1#2", "c3#1", "c4#1", "c5#1"]
```

过滤发生在分组计数之前，因此非目标值不会进入频次计算。表达式中的特征引用必须与模型特征集里的 `name` 完全一致；带点号的名称（如 `Item.i2i_top5_cluster_id`）可被表达式解析器识别。
