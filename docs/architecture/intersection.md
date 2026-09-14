# intersection 序列交集

表达式：`intersection(left, right)`，也支持命名参数 `intersection(left=left_values, right=right_values)`。

两个参数都必须是 SEQUENCE，支持 STRING、BOOLEAN、INT、BIGINT、DOUBLE。除数值类型可混用外，双方元素类型必须一致。不支持对象、事件序列、嵌套序列或标量参数。

结果去重，按左序列首次出现的顺序返回，保留左侧元素及其类型。结果实体域取双方并集。数值使用精确十进制比较，1、1L 与 1.0 相等；不会先转换为 double 导致大整数误匹配。非有限数值报错。null 元素仅与 null 匹配，最多保留一个；整个输入为 null 则报错。任一空序列或无交集返回空序列。

例如左序列 `["b", "a", "b", "c"]` 与右序列 `["a", "b", "d"]` 得到 `["b", "a"]`。可用 `get_seq_length(intersection(left_values, right_values))` 计算共同元素数量。

配置中的衍生特征示例（left_values/right_values 是已声明的 STRING/SEQUENCE 基础特征）：

```json
{
  "name": "common_values",
  "type": "STRING",
  "definition_type": "DERIVED",
  "expression": "intersection(left_values, right_values)",
  "entity_scopes": ["USER"],
  "value_shape": "SEQUENCE",
  "output_policy": "OUTPUT"
}
```

Single 支持 List 和 OperatorSequence 视图，返回不可修改的 List，不修改输入。Batch 使用 SCALAR_ADAPTER，逐行等价于 Single。所有集合均为调用内局部变量，算子无请求状态。平均时间复杂度 O(n+m)，右侧匹配集合空间 O(m)，另加输出空间。实现使用 JDK 1.8 语法与 API。
