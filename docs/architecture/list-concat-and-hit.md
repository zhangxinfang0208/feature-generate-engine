# `list_concat` 与 `hit` 算子

## `list_concat`

`list_concat(seq1, seq2)` 读取 `seq2` 的首元素，将其广播到 `seq1` 的每个元素：

```text
list_concat(["a", "b", "a"], ["电商"])
=> ["a#电商", "b#电商", "a#电商"]
```

默认分隔符是 `#`。第三个对象参数可覆盖分隔符：

```text
list_concat(seq1, seq2, {"delimiter":"|"})
```

两个输入都必须推断为 `SEQUENCE`，输出固定为 `STRING / SEQUENCE`。`seq2`
为空时求值失败；事件对象没有稳定的字符串投影，因此 `EVENT_SEQUENCE` 在构图期被拒绝，
运行时遇到 `Map` 元素也会防御性失败。算子没有原生 Batch Kernel，由标量适配器逐行执行。

## `hit`

`hit(seq_kv, seq_key)` 将 `seq_key` 转为查询集合，再按 `seq_kv` 原顺序扫描事件：

- 命中事件原样进入结果，额外字段和值类型不会被改写；
- `seq_kv` 中的重复事件会保留；
- `seq_key` 中的重复 key 不会放大结果；
- 未命中或空查询得到空事件序列。

运行时数据使用标准 JSON 事件数组，而不是 `['a':1]` 这类非 JSON 表达：

```json
{
  "seq_kv": [
    {"key": "a", "value": 1},
    {"key": "b", "value": 2},
    {"key": "a", "value": 3}
  ],
  "seq_key": ["a", "c"]
}
```

结果为：

```json
[
  {"key": "a", "value": 1},
  {"key": "a", "value": 3}
]
```

`seq_kv` 必须推断为 `EVENT_SEQUENCE / SEQUENCE`，每个事件必须是包含字符串字段
`key` 的对象；`seq_key` 必须为 `STRING / SEQUENCE`。输出透传主输入的
`EVENT_SEQUENCE / SEQUENCE`，实体域取两个输入实体域的并集。实现复杂度为
`O(seq_key.size + seq_kv.size)`，没有原生 Batch Kernel。

## 模型特征集与中间态

如果切片结果只供 `hit` 使用，不需要在模型特征集中声明中间特征，直接匿名嵌套：

```json
{
  "name": "hit_output",
  "type": "EVENT_SEQUENCE",
  "definition_type": "DERIVED",
  "expression": "hit(slice_by_indices(seq_kv, selected_indices), seq_key)",
  "value_shape": "SEQUENCE",
  "entity_scopes": ["USER"],
  "output_policy": "OUTPUT"
}
```

表达式解析后会生成匿名 `OperatorNode -> OperatorNode` 依赖；AST 不进入持久化计划，
因此不要求模型特征集为该中间节点命名。

如果同一中间结果会被多个输出复用，或需要独立治理和排查，可声明为
`INTERNAL_ONLY`：

```json
[
  {
    "name": "sliced_seq_kv",
    "type": "EVENT_SEQUENCE",
    "definition_type": "DERIVED",
    "expression": "slice_by_indices(seq_kv, selected_indices)",
    "value_shape": "SEQUENCE",
    "entity_scopes": ["USER"],
    "output_policy": "INTERNAL_ONLY"
  },
  {
    "name": "hit_output",
    "type": "EVENT_SEQUENCE",
    "definition_type": "DERIVED",
    "expression": "hit(seq_kv=sliced_seq_kv, seq_key=seq_key)",
    "value_shape": "SEQUENCE",
    "entity_scopes": ["USER"],
    "output_policy": "OUTPUT"
  }
]
```

`INTERNAL_ONLY` 特征参与可达 DAG 构建和执行，但不会出现在公共输出中。匿名与具名两种
写法的选择只影响配置可读性和复用方式，不改变 `hit` 的数据结构或过滤语义。
