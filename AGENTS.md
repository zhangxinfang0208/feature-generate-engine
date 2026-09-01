# 仓库贡献指南

## 项目结构与标准算子范围

本项目是一个基于 Java 21 的三层特征表达式 DAG 引擎参考实现。生产代码位于 `src/main/java/com/example/featuredag/`：`definition`、`expression` 和 `config` 定义输入；`logical` 构建逻辑 DAG；`planning` 和 `physical` 生成物理计划；`runtime` 执行计划；`operator` 提供算子协议与实现。

标准注册表当前显式提供以下 23 个算子：`discrete`、`log_base`、`slice_by_indices`、`find_indices`、`find_indices_any`、`get_seq_length`、`count_distinct`、`zip_concat`、`concat`、`append`、`join`、`list_concat`、`hit`、`group_count_concat`、`calc_delta_seq`、`to_int`、`to_bigint`、`min`、`max`、`add`、`sub`、`mul`、`div`。每个算子必须有独立 `.java` 文件，`InitialBusinessOperators` 维护唯一的显式清单，`OperatorRegistry.standard()` 直接注册该清单，不再增加纯转发聚合层。增删标准算子时必须同步清单、独立 JUnit 4 注册测试、使用文档和对应算子测试。

仓库可以按业务主题提供标准算子的公共 API Demo、共享配置和调测脚本；单个 Demo 无需覆盖全部标准算子，但其使用的每个算子都必须来自标准注册表或通过公共扩展入口显式注册。`src/test/java/com/example/featuredag/DagEngineSelfTest.java` 为冻结的存量自测，后续增量需求不得修改；新增 UT 必须写成 `src/test/java` 下独立的 JUnit 4 `*Test.java` 文件（见「测试与提交」）。辅助脚本位于 `scripts/`，编译产物写入 `target/`。

## 三层 DAG 构建约束

- C1 单向分层依赖：L0（`definition`/`expression`/`config`）→ L1（`logical`）→ 规划/物理层（`planning`/`physical`）→ 运行时（`runtime`）。禁止反向引用；规划层不得改写逻辑节点。
- C2 定义层：`FeatureDefinition` 构造后不可变；RAW 必须声明 entityScopes 且不得携带表达式，DERIVED 必须有表达式。所有校验在构造器内完成。
- C3 逻辑层：`LogicalDagBuilder` 从 targetFeatures 逆向构建可达子图；表达式 AST 构建后丢弃，不进入持久化计划模型。
- C4 无环约束：构建期用 DFS 三色标记检测依赖环，拓扑排序再次校验。
- C5 节点去重与命名：节点按 canonical 签名合并；ID 使用 `source:`、`literal:`、`operator:`、`feature:` 前缀。
- C6 声明与推断一致：声明类型、值形状和实体域必须与推断结果一致；数值类型仅允许沿 INT → BIGINT → DOUBLE 安全提升。
- C7 逻辑节点不可变：依赖通过 `NodeInput` 的节点 ID 与端口引用。
- C8 规划层只读：引用计数、可达根、缓存资格和大小估算放在 `NodePlanningMetadata`，融合由注册的物理改写规则匹配。
- C9 物理转换：每个未融合逻辑节点只产生一个物理输出槽；融合节点记录全部 consumed logical node IDs，并遵守共享节点与根节点安全约束。
- C10 环境决策：执行阶段、模式、缓存策略及 Single/Batch Kernel 只能由环境、实体域、算子语义、shape 和注册能力推导；核心层禁止按业务算子名特判。

## 算子实现与扩展

- `operator` 层通过 `OperatorSemantic` 声明逻辑语义，不得引用物理或运行时类型。
- 每个业务算子单独实现元数据、推断和求值；注册类只装配实例，不承载业务逻辑。
- `OperatorDefinition` 的 Single Kernel 是语义基准。Native `BatchOperatorKernel` 可选；未提供时使用 `SingleLoopBatchOperatorKernel`。
- 当前 23 个标准算子中仅 `find_indices`、`count_distinct`、`zip_concat`、`calc_delta_seq` 提供原生 `BatchOperatorKernel`（批内按 identity 键复用收益显著）；其余算子（包括 `find_indices_any`、`concat`、`append`、`join`、`list_concat`、`hit`）由 `SingleLoopBatchOperatorKernel` 逐行适配。新增算子默认不提供原生 Batch，须按「每行可省计算量 × 批内重复度」成本模型评估后再实现；不得在没有基准数据时增加原生 Batch。
- Batch 必须逐行等价于 Single，保持行数和顺序；Kernel 实例必须无请求状态且可并发复用。
- `planning`、`physical`、`runtime` 禁止按业务算子名增加分支；DAG 模式通过 `PhysicalRewriteRule` 注册，专用算法通过 `PhysicalExecutorRegistry` 注册。
- 缓存只允许 deterministic 且 sideEffectFree 的算子（`sideEffectFree()` 默认 false，内置算子经 `AbstractBuiltinOperator` 显式声明 true；新算子必须显式声明纯度）；缓存 key 必须覆盖域、具体序列视图和所有变化输入。
- 通用候选批去重路径（CANDIDATE_KEY）与算子估算成本（`estimatedCost`）已移除：批内复用由原生 Batch 的 identity 键承担；`CachePolicy.CANDIDATE_KEY`/`ExecutionMode.CANDIDATE_KEY` 仅保留供融合改写标注，`CachePolicy.REQUEST` 为规划期预留标记、运行时一期不消费。
- 详细约束见 `docs/architecture/operator-optimization-extension.md` 和 `docs/architecture/operator-single-batch-execution.md`。

## Java 版本约束

项目整体构建基线仍为 Java 21。全部标准内置算子、直接共用的 `operator.builtin` 支撑代码以及面向标准算子的 Demo 必须只使用 JDK 1.8 可用的语言特性和标准库 API，不得使用 `record`、文本块、模式匹配 `instanceof`、`List.of/copyOf`、`Stream.toList`、`List.getFirst/getLast` 等更高版本能力。

该约束保证首期算子源码便于独立抽取或代码生成，不表示整个仓库可以在 JDK 1.8 下编译。

## 构建与测试

- `mvn clean package`：使用 Java 21 编译，生成 thin JAR 和包含 Jackson 的 `target/feature-dag-engine-1.0.0-SNAPSHOT-all.jar`；产物不包含 Demo `Main-Class`。`mvn test`/`mvn package` 会自动执行 JUnit 4 新增 UT（`*Test.java`），但不会执行 `DagEngineSelfTest`。
- `./scripts/run-self-test.sh`：编译主代码和测试代码，先以 `java -ea` 执行存量自测，再运行 `mvn test` 执行 JUnit 4 测试——提交前必须显式运行该脚本，两个环节都通过才算绿。
- `./scripts/run-initial-operator-demos.sh [all|scalar|sequence|batch]`：在 Bash 中运行首期算子调测入口。
- `./scripts/run-initial-operator-demos.ps1 [all|scalar|sequence|batch]`：在 PowerShell 中运行首期算子调测入口。

提交前必须显式运行自测试；`mvn package` 只编译测试源码，不会自动执行 `DagEngineSelfTest`。

## 编码风格

使用四个空格缩进和 UTF-8。每个文件只声明一个公共顶级类型。类名和枚举名使用 `PascalCase`，方法与变量使用 `camelCase`，枚举常量使用 `UPPER_SNAKE_CASE`。领域类型应小型、不可变，并在构造器或 Builder 中显式校验。

层间转换点使用中文注解说明转换语义并引用 C1–C10；核心链路不引入运行时日志。

## 测试与提交

存量自测（`DagEngineSelfTest`、`FeatureValueCodecSelfTest`、`ModelFeatureSetInitialOperatorsSelfTest` 等）使用 Java `assert`，由 `run-self-test.sh` 以 `java -ea` 显式运行，保持零依赖。`DagEngineSelfTest.java` 已冻结，增量功能不得向其中追加或修改注册、推断、求值、异常、Batch 或 Demo 断言。**新增单元测试一律使用 JUnit 4**（`org.junit.Test` + `Assert`），写入 `src/test/java` 下独立的 `*Test.java` 文件，由 surefire 在 `mvn test`/`mvn package` 自动执行。标准算子测试应覆盖注册、推断、执行、异常和 Batch 路由；事件序列算子还必须覆盖公共 API 编解码、匿名嵌套中间态和 `INTERNAL_ONLY` 具名中间态。

提交标题应简短并使用祈使语气。每次提交聚焦一个主题；拉取请求说明行为变化、受影响架构层和已运行的验证命令。
