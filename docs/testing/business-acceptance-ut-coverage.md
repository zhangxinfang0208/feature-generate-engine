# 首期 8 算子回归 UT 覆盖矩阵

本矩阵只记录首期 8 个算子的专项回归，不再限制标准注册表范围。当前标准算子清单以
`InitialBusinessOperators` 和 `AGENTS.md` 为准；后续算子使用各自独立的 JUnit 4 测试验收。

| 算子 | 注册/独立类 | 求值 | 非法输入 | DAG 类型与 shape | Batch 路由 |
| --- | --- | --- | --- | --- | --- |
| `discrete` | 已覆盖 | 已覆盖 | 边界非递增 | INT / SCALAR | SCALAR_ADAPTER |
| `log_base` | 已覆盖 | 已覆盖 | 非法底数 | DOUBLE / SCALAR | SCALAR_ADAPTER |
| `slice_by_indices` | 已覆盖 | 已覆盖 | 实现内校验 | 保留输入序列类型 / SEQUENCE | SCALAR_ADAPTER |
| `find_indices` | 已覆盖 | 已覆盖 | 实现内校验 | INT / SEQUENCE | NATIVE |
| `get_seq_length` | 已覆盖 | 已覆盖 | 实现内校验 | INT / SCALAR | SCALAR_ADAPTER |
| `count_distinct` | 已覆盖 | 已覆盖 | 实现内校验 | INT / SCALAR | NATIVE |
| `zip_concat` | 已覆盖 | 已覆盖 | 序列不等长 | STRING / SEQUENCE | NATIVE |
| `calc_delta_seq` | 已覆盖 | 已覆盖 | 实现内校验 | DOUBLE / SEQUENCE | NATIVE |

此外，`FeatureValueCodecSelfTest` 继续覆盖公共输入/输出值编解码。性能、业务平台接入、模型发布、容量和 SLA 不以本地 UT 冒充通过。

扩展算子专项覆盖：

| 算子 | 注册/独立类 | 求值 | 非法输入 | DAG 类型与 shape | Batch 路由 |
| --- | --- | --- | --- | --- | --- |
| `find_indices_any` | 已覆盖 | 多目标集合匹配、源顺序 | 错误 shape、逐行异常隔离 | INT / SEQUENCE | NATIVE |
| `list_concat` | 已覆盖 | 广播 `seq2[0]`、分隔符配置 | 空 `seq2`、事件元素、错误 shape | STRING / SEQUENCE | SCALAR_ADAPTER |
| `hit` | 已覆盖 | key 集合过滤、顺序与重复事件 | 非事件输入、缺失/非字符串 key | EVENT_SEQUENCE / SEQUENCE | SCALAR_ADAPTER |

三个首期 Demo 的 `run()` 会纳入 `DagEngineSelfTest`，用于覆盖单行公共 API、序列输出编码和离线多行
Batch 公共 API。这些 Demo 仍以首期 8 个算子为主题，不代表标准注册表只允许这 8 个算子。

## 执行方式

使用 JDK 21 编译项目，然后显式启用 Java 断言：

```bash
mvn clean package
./scripts/run-self-test.sh
```

标准 `operator.builtin` 源码需保持 JDK 1.8 语法/API 兼容；该要求不改变整个项目的 Java 21 构建基线。
