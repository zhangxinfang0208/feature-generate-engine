# Feature DAG Engine

这是一个三层特征表达式 DAG 引擎参考实现。引擎将特征定义转换为不可变逻辑 DAG，经只读规划分析生成物理计划，再由 Runtime 执行。

项目整体使用 Java 21 构建。首期内置算子源码位于 `operator.builtin`，这部分代码只使用 JDK 1.8 可用的语法和 API，便于后续独立抽取或生成；这不代表整个仓库可直接使用 JDK 1.8 编译。

## 标准算子

`OperatorRegistry.standard()` 注册以下 23 个算子：

| 算子 | 签名 | 结果 |
| --- | --- | --- |
| `discrete` | `discrete(value, boundaries)` | 对数值标量/序列分桶；序列元素共享同一组边界 |
| `log_base` | `log_base(value, base, maxValue)` | value/base 支持等长序列与标量广播，maxValue 保持共享标量 |
| `slice_by_indices` | `slice_by_indices(sequence, indices)` | 按下标选取序列元素 |
| `find_indices` | `find_indices(sequence, target)` | 返回所有匹配元素的下标 |
| `find_indices_any` | `find_indices_any(sequence, targets)` | 返回命中任一目标值的全部下标并保持源顺序 |
| `get_seq_length` | `get_seq_length(sequence)` | 返回序列长度 |
| `count_distinct` | `count_distinct(sequence)` | 返回不同元素个数 |
| `zip_concat` | `zip_concat(sequence1, sequence2, ...)` | 按位置使用 `#` 拼接等长序列 |
| `concat` | `concat(value1, value2, ...)` | 使用可配置分隔符拼接两个或更多标量 |
| `append` | `append(valueOrSequence1, valueOrSequence2)` | 按参数顺序把两个标量或序列合并为一个序列 |
| `join` | `join(sequence, delimiter?)` | 使用默认 `#` 或指定字符串分隔符把序列折叠为字符串 |
| `list_concat` | `list_concat(sequence, suffixSequence, config?)` | 将后缀序列首元素广播并逐元素拼接 |
| `hit` | `hit(eventSequence, keys)` | 按 key 集合过滤事件序列 |
| `group_count_concat` | `group_count_concat(sequence, {"delimiter":"#"})` | 按首次出现顺序输出“值 + 分隔符 + 频次”序列 |
| `calc_delta_seq` | `calc_delta_seq(sequence, baseline)` | 逐元素计算 `value - baseline` |
| `to_int` | `to_int(value)` | 数值或十进制数字字符串标量/序列转 32 位 int 载体；序列逐元素转换并保序，小数向零截断，超范围失败 |
| `to_bigint` | `to_bigint(value)` | 数值或十进制数字字符串标量/序列转 64 位 bigint 载体；序列逐元素转换并保序，小数向零截断，超范围失败 |
| `min` | `min(value1, value2, ...)` | 数值标量/等长序列逐元素最小值；标量广播，相等保留最左输入 |
| `max` | `max(value1, value2, ...)` | 数值标量/等长序列逐元素最大值；标量广播，相等保留最左输入 |
| `add` | `add(value1, value2)` | 数值标量/等长序列逐元素加法；标量广播 |
| `sub` | `sub(value1, value2)` | 数值标量/等长序列逐元素减法；标量广播 |
| `mul` | `mul(value1, value2)` | 数值标量/等长序列逐元素乘法；标量广播 |
| `div` | `div(value1, value2)` | 数值标量/等长序列逐元素除法；标量广播，分母为 0 返回 0.0 |

每个算子都拥有独立的 `.java` 实现类，负责自己的元数据、类型/shape 推断和单值求值。`InitialBusinessOperators` 是唯一的标准算子清单，`OperatorRegistry.standard()` 直接注册该清单。

`find_indices`、`count_distinct`、`zip_concat`、`calc_delta_seq` 提供原生 `BatchOperatorKernel`（批内按 identity 键复用收益显著）；其余 19 个（包括 `find_indices_any`、`concat`、`append`、`join`、`list_concat`、`hit`、`group_count_concat`）不提供原生 Batch，由 `SCALAR_ADAPTER` 逐行适配。`find_indices` 的 Native Batch 首次遇到同组内的序列实例时直接扫描，第二次查询该实例才建立索引，后续查询复用索引，避免独立序列承担全量索引成本。

## 算子异常与衍生默认值

在特征 DAG 内执行时，所有已注册算子（包括公共扩展入口注册的算子）的 Kernel 若抛出
`RuntimeException`，运行时会把失败传递到所属衍生特征的 `FEATURE_OUTPUT` 边界：

- 特征配置了非空 `dft`：Single 使用一次默认值；Batch 只替换失败的 row、request group 或
  candidate，健康单元保持原顺序和值并继续执行；
- 特征未配置非空 `dft`：本次生成仍失败，公共 `FeatureGenerationException` 保留特征名和原始 cause；
- 嵌套表达式中的失败单元会短路后续算子。短路只跳过该失败单元，不会终止进程，也不会阻止
  其他健康 Batch 单元或无关 DAG 分支执行；
- 引擎不会重试失败算子。直接调用 `OperatorRegistry.evaluate/evaluateBatch` 仍保持 fail-fast。

该能力只覆盖算子 Kernel 内的运行期异常。配置、表达式解析/推断、RAW 解码与绑定、DAG/物理计划、
Batch 协议、缓存类型、输出编码错误以及 `Error` 不会被衍生 `dft` 掩盖。完整边界和扩展兼容规则见
[`docs/architecture/operator-failure-default-fallback.md`](docs/architecture/operator-failure-default-fallback.md)。

数值极值和四则运算的序列契约见
[`docs/architecture/numeric-sequence-operators.md`](docs/architecture/numeric-sequence-operators.md)。

## 目录结构

```text
src/main/java/com/example/featuredag/
├── api          # 公共 init/generate API
├── config       # JSON 配置加载与映射
├── definition   # 特征定义和值类型
├── expression   # 表达式 AST 与解析
├── demo         # 首期 8 个算子的公共 API 调测入口
├── logical      # 逻辑 DAG 构建
├── planning     # 只读规划分析
├── physical     # 物理计划与改写规则
├── runtime      # 计划执行
└── operator
    └── builtin  # 标准算子独立实现与显式注册清单
```

仓库不包含依赖非首期算子的 Demo 或 JMH 基准代码。

## 构建与验证

环境要求：JDK 21 或更高版本，以及 Maven。

```bash
mvn clean package
./scripts/run-self-test.sh
```

`mvn clean package` 会生成 thin JAR，以及包含并重定位 Jackson 的 `target/feature-dag-engine-1.0.0-SNAPSHOT-all.jar`。这两个产物都是库文件，不包含 Demo `Main-Class`。

自测试使用 Java `assert`，覆盖标准算子的注册清单、独立实现类与参数数量，以及首期 8 个算子的求值、异常校验、Batch 路由和 DAG 类型/shape 推断。

## 调测 Demo

三个 Demo 都通过公共 `FeatureDagEngine.init/generate` API 执行，并共用
[`src/main/resources/demo/initial-operators.json`](src/main/resources/demo/initial-operators.json)：

- `ScalarOperatorsDemo`：调测 `discrete`、`log_base`、`get_seq_length`、`count_distinct`；
- `SequenceOperatorsDemo`：调测 `slice_by_indices`、`find_indices`、`zip_concat`、`calc_delta_seq`；
- `OfflineBatchOperatorsDemo`：用两行输入一次调测全部 8 个算子的 Batch 适配路径。

Windows PowerShell 一键运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/run-initial-operator-demos.ps1 all
powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/run-initial-operator-demos.ps1 scalar
powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/run-initial-operator-demos.ps1 sequence
powershell -NoProfile -ExecutionPolicy Bypass -File ./scripts/run-initial-operator-demos.ps1 batch
```

Bash 一键运行：

```bash
./scripts/run-initial-operator-demos.sh all
```

也可以在 IDE 中直接运行三个 Demo 的 `main` 方法。每个 Demo 都会先校验预期结果，再打印输出；
修改调测数据时可直接编辑对应 Demo 中的 `row.put(...)`，修改表达式或元数据时编辑共享 JSON 配置。
Demo 源码本身只使用 JDK 1.8 语法/API，但运行完整项目仍需要 JDK 21。

## 架构约束

- 定义层、逻辑层、规划/物理层、运行时保持单向依赖。
- 逻辑 DAG 按目标特征逆向构建，并执行环检测、canonical 节点去重和声明/推断一致性校验。
- 规划器只读逻辑 DAG；通用规划事实保存在外置元数据中。
- 物理优化依赖注册的算子语义和改写规则，核心规划与运行时代码不按业务算子名分支。
- 单值语义由 `SingleOperatorKernel` 定义；未实现 Native Batch 的算子由框架逐行适配。

详细设计见：

- [`docs/architecture/calc-delta-seq.md`](docs/architecture/calc-delta-seq.md)
- [`docs/architecture/append-and-join.md`](docs/architecture/append-and-join.md)
- [`docs/architecture/operator-optimization-extension.md`](docs/architecture/operator-optimization-extension.md)
- [`docs/architecture/operator-single-batch-execution.md`](docs/architecture/operator-single-batch-execution.md)
- [`docs/architecture/operator-failure-default-fallback.md`](docs/architecture/operator-failure-default-fallback.md)
- [`docs/architecture/online-grouped-batch-execution.md`](docs/architecture/online-grouped-batch-execution.md)
- [`docs/architecture/runtime-observability.md`](docs/architecture/runtime-observability.md)
- [`docs/architecture/physical-node-fusion.md`](docs/architecture/physical-node-fusion.md)
