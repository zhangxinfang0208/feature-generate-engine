# 离线代码优化版：PR #46

基线：zhangxinfang0208/feature-generate-engine，commit `803b8573c9a79385eaf4f8e81fe75157a26a27e9`。

## 使用方式

此变更基于 PR #46 的离线源码快照。两个 Scala 文件需要一起合入完整业务工程，保留原有 package/import。

`optimization-parameters.json` 是合并进现有任务参数的示例，不是完整配置。新增参数直接通过 `taskMeta.parameter` 读取，无需修改 TransformParamModule。

## 已实施的修改

1. 主流程用本次执行的 `ownedHiveCaches` 管理新建缓存，不再遍历 SparkContext 并清空所有持久化 RDD。已有缓存不会登记为本任务所有。外部编码器自行建立的缓存仍需由其自身管理。
2. 全量 fit 或增量 fit 路径，在 train DAG 已启用缓存且本任务持有 train 原始缓存时，先完整物化 train DAG，再释放 train 原始缓存。纯历史版本 transform 路径不增加这次预热 action。预热计数不覆盖业务样本数。
3. train/valid/test 写出完成后逐个释放对应 DAG/Hive 缓存。train 如果还需要 meta 采样，则将 DAG 缓存保留至最终清理；广播始终保留至所有消费者结束。失败路径同样清理本任务持有的 Hive 缓存。
4. `saveHdfsFile` 对所有数据类型接受显式正数输出分区。主流程增加独立参数，避免把 train 分区数无条件套用到较小的 valid。
5. 未指定输出分区时，将原来的 count 与抽样大小统计合并为一次 RDD action；仅抽中的行执行 `toString/getBytes`。支持空输入、空样本，避免 `reduce` 空集合报错及输出分区数为零。仍会全量遍历，此改动不意味着只读取 0.1% 数据。
6. `computeSampleWeight` 只读取一次日期范围聚合结果，消除重复 `first()`。
7. DAG 默认 batchSize 从 1000 改为 200；显式传入的正数 batchSize 优先。目标是降低长序列临时对象峰值，最佳吞吐仍需实测。
8. 修复快照中的 `FeatureFitAndTransformNew\.` 和 `:_\*` 一类 Markdown 遗留转义；没有全局删除业务字符串里的反斜杠。

## 参数

| 参数 | 本版默认/行为 |
|---|---|
| batchSize | 200；建议同数据对比 100/200/500/1000 |
| materializeTrainDagBeforeFit | true；设 false 关闭显式 train 预热与该处原始缓存提前释放 |
| trainOutputRepartitionNum | 回退原 outputRepartitionNum |
| validOutputRepartitionNum | -1：自动估算；正数：跳过估算 |
| testOutputRepartitionNum | -1：自动估算；正数：跳过估算 |
| storageLevel | 原有参数，不修改其默认值 |
| dagOutputStorageLevel | 原有参数，仍需显式配置缓存级别 |

第一轮可保持用户的 `MEMORY_AND_DISK_SER`、80g、2 cores、400 executors，避免同时调整太多变量。需要按实际输出体积填写三个输出分区数，不能仅由 3 亿/2 亿行推算固定值。按日期保存时，显式分区数应用到每个日期的写出。

## 行为边界与待做项

- 保留特征值、桶数、低频过滤规则、序列顺序和截断规则的现有实现；批次大小和采样算法变化需要在业务环境核对结果。文件数量/分布可能改变。
- 预热 train 增加一次 action，以换取 fit 前释放原始缓存的时机；是否净提速需要实测。缓存不是 checkpoint，丢块/Executor 丢失仍可能重算。释放 originTrainDF 的缓存后，下游使用返回值可能重新读源数据。
- valid/test 的初始 count 和 Hive 缓存仍保留；没有将整个流程重构为逐数据集加载。这避免改变跨数据集业务统计、保存配置和 meta 采样依赖。
- 自动列裁剪、合并 autoHash/各类 fit、跨数据集延迟加载未在本版实施。这些需要完整业务依赖与模型特征集，不能仅凭快照安全推断。
- 不改 autoHash 为近似 distinct，也不强制将 DAG 缓存改成 DISK_ONLY。
- 原快照已有的前置条件仍然存在，例如 train/valid/test 均要求非零、originTrainDF 在空值判断前访问 trainData。此版未重构这些业务规则。

## 验证范围

- 两个 Scala 文件通过 Tree-sitter Scala 语法解析，无 ERROR/missing 节点。这不是 Scala 编译或类型检查。
- 优化差异已在原始快照上验证可准确应用。
- 已尝试执行仓库要求的 `bash scripts/run-self-test.sh`，因环境缺少 Maven 而退出（`Maven is required.`），未通过自测。
- 未执行业务工程编译、Spark 作业或 5 亿行性能测试。原快照缺少 package/import、业务类依赖及可运行的离线工程入口，当前环境无 Scala 编译器/Spark。

上线前在完整业务仓库编译，并使用同一份固定输入比较：特征与标签值、序列顺序、样本数量、featureMap/桶数、meta 输出，以及总耗时、GC、峰值内存、缓存、Shuffle spill。覆盖全量 fit、增量 fit、历史 transform、externalOnly、materialSwitch、saveByColumn、meta、无 DAG/无缓存等实际启用分支。不能将语法解析通过视为业务验证通过。
