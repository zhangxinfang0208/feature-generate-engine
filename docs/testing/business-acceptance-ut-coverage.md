# 首期算子 UT 覆盖矩阵

首期只验收标准注册表中的 8 个算子，不在 UT 中保留或重新实现其他标准算子。原业务验收矩阵中依赖已移除 Demo、融合样例或非首期算子的项目，不再声明为当前 UT 覆盖。

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

三个首期 Demo 的 `run()` 会纳入 `DagEngineSelfTest`，用于覆盖单行公共 API、序列输出编码和离线多行
Batch 公共 API。Demo 和测试只允许使用首期 8 个算子。

## 执行方式

使用 JDK 21 编译项目，然后显式启用 Java 断言：

```bash
mvn clean package
./scripts/run-self-test.sh
```

首期 `operator.builtin` 源码需保持 JDK 1.8 语法/API 兼容；该要求不改变整个项目的 Java 21 构建基线。
