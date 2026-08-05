# 业务验收场景 UT 覆盖矩阵

来源：`特征表达式DAG引擎业务验收测试方案.docx`。

本矩阵只把当前仓库中可确定、可重复验证的引擎行为列为 UT 覆盖。依赖特征平台、训练系统、模型发布/热更新、RPC 服务或容量环境的验收项，不以单元测试冒充通过。

## 本次新增或加强的 UT

| 场景编号 | 覆盖内容 |
| --- | --- |
| FP-003~FP-008 | USER/SCENE/ITEM 范围、派生范围推导、缺失引用、语法错误、原始范围校验、内部特征不泄漏 |
| TR-001、TR-002、TR-004~TR-009 | 全部 OUTPUT 选择、单行语义、同行业结果、View 消费与输出物化、默认值、空序列 |
| DG-001~DG-010 | 目标闭包、多级依赖、公共节点复用、范围/形态推导、离在线子图、环检测、`count` 类型校验 |
| ON-006~ON-010 | 多候选、顺序保持、零候选、单候选、候选字段默认/拒绝 |
| EX-001~EX-008 | Request Shared/Candidate Batch 分阶段、广播及跨阶段依赖 |
| SC1-001~SC1-007 | 同行业一致、有效输入去重、4→3 指标、0 结果、顺序恢复、在线融合、离线保留序列语义 |
| SC2-001~SC2-008 | 共享底层块、零复制 View、Range/Index/Bitmap、count 消费 View、View 链归一化、输出边界物化 |
| CO-001~CO-008 | 单候选/多候选离在线一致、相同配置参数、子图语义、默认值、空序列、`1e-9` 容差、乱序映射 |
| ER-002、ER-003、ER-010 | 未注册算子、算子参数数量、循环依赖在构图阶段阻断 |

## 修改前已经存在的回归基础

这些行为原本已在 `DagEngineSelfTest` 中验证，本次保留并纳入全量回归：

- JSON 字段兼容、字符串布尔值、未知业务字段保留和配置文件路径初始化。
- 中间特征依赖闭包与 `INTERNAL_ONLY` 输出过滤，对应 FP-008 的基础行为。
- 在线三候选顺序、同行业计数及 3→2 去重，对应 ON-006、ON-007、SC1-001、SC1-002。
- 序列 OUTPUT 物化和底层 `SequenceBlock` 共享，对应 TR-007、SC1-007、SC2-008。
- 在线引擎并发复用、重复名称/存储名、禁用依赖、环境不匹配、缺失输入及环依赖校验。

## 外部、集成或性能环境范围

下列场景未标记为 UT 通过：

| 场景编号 | 原因及建议验收层级 |
| --- | --- |
| FP-001、FP-002 | 需要特征平台发布、版本保存及多模型引用能力 |
| TR-003、TR-010 | 需要 Spark 任务编排、训练任务复用及真实用户分组批处理指标 |
| MD-001~MD-008 | 需要训练系统、权重文件和模型专属产物生命周期 |
| ON-001~ON-005 | 需要模型部署、流量切换、热更新及旧实例保活；ON-002/ON-003 的本地构图架构可由现有 API 支持，但没有部署生命周期可验收 |
| EX-009 | Candidate Set/rank 算子尚未实现；当前仅能按未注册算子拒绝 |
| SC2-009 | 需要 250 个最终序列特征的真实写出体积测试 |
| ER-001、ER-004~ER-009 | 需要在线数据绑定、明确的脏数据策略、限流/截断策略、缓存/索引故障注入及模型回滚能力 |
| PF-001~PF-008 | 必须在代表性规模和 SLA 环境压测；UT 仅覆盖 PF-001 的阶段结构、PF-002 的小规模去重指标、PF-003 的 View 结构，不代表容量或时延通过 |

## 执行方式

使用 JDK 21 编译，然后显式启用 Java 断言运行自测试主类：

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q clean package
mvn -q -DskipTests test-compile dependency:build-classpath "-Dmdep.outputFile=target/test-classpath.txt"
$cp = "target/test-classes;target/classes;" + (Get-Content -Raw target/test-classpath.txt)
java -ea -cp $cp com.example.featuredag.DagEngineSelfTest
```
