# cos_sim_seq 向量序列余弦相似度

`cos_sim_seq(emb_seq, target_emb)` 已加入 `OperatorRegistry.standard()`。
表达式通过框架的解析、推断和运行时执行；无需在 planning、physical 或 runtime 增加算子名分支。

## 输入与输出

| 参数 | 声明类型 | 运行时值 |
| --- | --- | --- |
| `emb_seq` | `OBJECT + SEQUENCE` | 序列中的每个元素为数值 `List` |
| `target_emb` | `DOUBLE + SEQUENCE` | 目标向量，元素为有限数值 |
| 输出 | `DOUBLE + SEQUENCE` | 每个 embedding 与目标的余弦相似度，长度和顺序与 `emb_seq` 一致 |

两输入的实体域取并集。外层序列和目标序列支持 `List` 以及框架的 `OperatorSequence` 视图；
每个 embedding 必须为 `List`。不隐式接受字符串数值；运行时 `Number` 转为 double 后必须有限。

例如，已声明 `emb_seq = [[1, 0], [0, 1]]`、`target_emb = [1.0, 0.0]` 时：

```text
cos_sim_seq(emb_seq, target_emb) → [1.0, 0.0]
```

公共 API 特征配置片段：

```json
[
  {"name":"emb_seq","type":"OBJECT","definition_type":"BASE",
   "entity_scopes":["USER"],"value_shape":"SEQUENCE"},
  {"name":"target_emb","type":"DOUBLE","definition_type":"BASE",
   "entity_scopes":["USER"],"value_shape":"SEQUENCE"},
  {"name":"similarities","type":"DOUBLE","definition_type":"DERIVED",
   "value_shape":"SEQUENCE","expression":"cos_sim_seq(emb_seq, target_emb)"}
]
```

## 边界与异常

- 空 embedding 序列返回 `[]`，目标仍须为有效数值序列。
- 任一向量为零向量时，相似度为 `0.0`；两向量均为零维时同样返回 `0.0`。
- 每个 embedding 必须与目标维度一致。维度不匹配、非 List embedding、非数值、`null`、NaN 或无穷值抛出 `IllegalArgumentException`。
- 零目标不会跳过 embedding 校验。错误包含向量位置或维度，便于定位。
- 返回不可变列表，不修改输入。直接调用注册表时异常立即传播；公共 API 沿用已有衍生特征默认值回退规则。

## 计算与 Batch

按 `dot(embedding, target) / (norm(embedding) * norm(target))` 计算。
每次 Single 调用仅累计一次目标模长；遍历每个 embedding 时同步累计点积和自身模长。
累加采用缩放表示，避免直接平方引起大数溢出或极小数下溢，最后将浮点舍入结果钳制到 `[-1, 1]`。
不创建中间向量；对支持常数时间索引的序列，计算复杂度为 `O(目标维度 + 序列长度 × 向量维度)`，
除输出外仅使用常量空间。

算子无请求状态，显式声明确定性和无副作用，源码兼容 Java 8。
未添加原生 Batch，使用 `SingleLoopBatchOperatorKernel`，逐行保持 Single 语义、行数和顺序。

独立 JUnit 4 测试：`CosSimSeqOperatorTest`，覆盖注册、推断、异常、数值边界、视图、Batch 路由及公共 API 嵌套选择。
