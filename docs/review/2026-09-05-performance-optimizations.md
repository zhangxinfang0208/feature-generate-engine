# 性能热点优化与验证

本次落实性能扫描中建议优先处理的三项。基线为 `9d732b0`，不改变标准算子清单、Single/Batch 路由、特征默认值或 DAG 分层约束。

## 实现范围

1. `FindIndicesOperator`：同一批中首次遇到 `(groupIndex, sequence identity)` 时直接扫描；第二次成功进入该序列的查询路径时建立全量索引，随后复用。索引只在调用内存活，具体序列视图和组边界继续隔离。保留 Java 8 源码能力，不在规划或运行时按业务算子名增加分支。
2. `FeatureOutputEncoder` / `ExternalValueMaterializer`：把最终 `seq_max_length` 传给物化器，仅遍历需要返回的顶层序列前缀；保留元素中的嵌套 Map/List 完整物化。上游序列、匿名中间态、INTERNAL_ONLY 具名中间态和下游求值不受截断影响。物化器返回的数据已独立且不可变，编码器直接复用，并在必要时补齐默认值，减少重复复制。
3. `FeatureInputDecoder`：Long 直接复用；Byte/Short/Integer 精确转换为 Long。BigInteger、BigDecimal、浮点和其他 Number 保持原来的精确校验，拒绝小数、非有限值和溢出。解码器新建的结果列表直接冻结，避免再次复制引用数组。

扫描中的通用 API 所有权传递、Batch 失败恢复快速路径、运行时观测精简和规划元数据复用未在本次扩展实现；它们仍需要各自的负载验证和接口设计。

## 局部性能对比

环境：Windows，Microsoft JDK 21。将基线的四个生产文件通过 `git show HEAD:<path>` 提取到 `target/performance-scan/baseline-src` 并编译，基线进程优先加载这些 class；当前进程加载 `target/classes`。其余代码与资源相同，未修改工作区源码来切换版本。

算子使用现有 `OperatorBatchComparisonDemo`，每组测试独立 JVM：序列长度 200、2 组、每组 200 行、10 轮预热、15 轮测量。表中为单次进程内的 median，单位 ms。

| 场景 | 路径 | 优化前 | 优化后 |
|---|---|---:|---:|
| 每行独立序列 | Single | 0.369 | 0.258 |
| 每行独立序列 | Scalar Adapter | 0.328 | 0.237 |
| 每行独立序列 | Native Batch | 2.820 | 0.328 |
| 组内共享两个序列实例 | Single | 0.346 | 0.351 |
| 组内共享两个序列实例 | Scalar Adapter | 0.335 | 0.335 |
| 组内共享两个序列实例 | Native Batch | 0.142 | 0.141 |

Native Batch 独立序列场景的这次局部测量约快 8.6 倍，共享场景基本持平。跨 JVM 的 Single 路径也有波动，以上结果不是稳定收益承诺，更不是业务端到端吞吐或延迟结论。

参数分别为：

```text
com.example.featuredag.demo.OperatorBatchComparisonDemo 200 2 200 10 15 1 1 0
com.example.featuredag.demo.OperatorBatchComparisonDemo 200 2 200 10 15 0 1 0
```

API 局部探针在初始化后直接调用解码/编码组件，不包含请求构造和其他运行时成本：

| 验证项 | 优化前 | 优化后 |
|---|---:|---:|
| 10,000 个已有 Long 的解码分配（bytes/call） | 1,280,321 | 40,281 |
| 100,000 项序列输出前 10 项的元素读取次数 | 100,000 | 10 |

分配使用 `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`，100 次预热后统计 100 次调用的均值。输出值另行检查等价；元素读取计数是确定性检查，不使用耗时阈值。临时探针与原始输出位于 `target/performance-scan/`，会被 `mvn clean` 清除。

## 回归验证

新增三个独立 JUnit 4 测试类，共 15 项：

- `FindIndicesAdaptiveBatchTest`：独立序列不建全量索引、跨组隔离、高复用路径、null/未命中、失败行恢复、具体视图与逻辑位置、结果不可变。
- `BigintInputFastPathTest`：Long 复用、混合整数精确转换、边界值、精确十进制、非法值和防御复制。
- `SequenceOutputMaterializationTest`：前缀读取计数、Scalar 包装、嵌套值完整性、视图顺序、默认补齐、无限长度、公共 API 在线/离线 Single/Batch 等价、上游完整长度及事件中间态。

定向测试通过；全量 `mvn test` 为 **218 项、8 项失败、0 项错误**，与改动前 `mvn clean test` 的 **203 项、相同 8 项失败**相比没有新增失败。已有失败如下：

```text
HwdspClick365dAllOperatorsTest#generatedValuesExactlyMatchThePublishedResultSet
HwdspClick365dAllOperatorsTest#offlineBatchMatchesSingleAndCoversNoTargetSlot
HwdspClick365dBusinessCasesTest#coversNoMatchAndMisalignedSequenceBoundaries
HwdspClick365dBusinessCasesTest#generatesExpectedPackageStrength
HwdspClick365dFullRegistryTest#documentedSampleRowsMatchEngineOutputs
HwdspClick365dFullRegistryTest#offlineBatchMatchesSingleAndHandlesNoTargetCategory
HwdspClick365dFullRegistryTest#verbatimPlatformConfigParsesBuildsAndGenerates
TransformTestExtendedOperatorsTest#batchMatchesSingleAndNoMatchRowKeepsLiteralBaseline
```

已显式运行 `bash scripts/run-self-test.sh`。脚本在存量 `ModelFeatureSetInitialOperatorsSelfTest.assertPaddedDoubleSequence` 处停止：`adjusted_scores[0]: expected=1.0, actual=-1.0`，尚未进入脚本后面的 JUnit 阶段，因此另行运行了全量 `mvn test`。优先加载基线四个 class 后再次执行 `java -ea ... DagEngineSelfTest`，复现同一错误。

未修改冻结自测或业务样例预期来掩盖上述失败。当前验证状态不是全量绿；这些基线问题仍需单独解决。
