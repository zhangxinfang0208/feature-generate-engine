# 仓库贡献指南

## 项目结构与模块组织

本项目是一个基于 Java 21 的三层特征表达式 DAG 引擎参考实现。生产代码位于 `src/main/java/com/example/featuredag/`，并按职责划分：`definition` 和 `expression` 定义输入与表达式；`logical` 构建逻辑 DAG；`planning` 和 `physical` 完成优化及物理计划转换；`runtime` 执行计划；`operator` 提供算子行为。`demo` 仅用于可运行示例，不应承载核心抽象。无外部依赖的集成测试位于 `src/test/java/com/example/featuredag/DagEngineSelfTest.java`。辅助脚本存放在 `scripts/`，编译产物和 JAR 文件统一写入 `target/`。

## 三层 DAG 构建约束

引擎按「定义 → 逻辑 → 物理」三层构建 DAG，各层职责与约束如下；代码中的中文注解均引用此处编号（如 C3），修改对应逻辑时应保持注解与约束同步。

- C1 单向分层依赖：L0 定义层（`definition`/`expression`/`config`）→ L1 逻辑层（`logical`）→ 规划/物理层（`planning`/`physical`）→ 运行时（`runtime`）。上层可引用下层类型，禁止反向引用；规划层不得改写逻辑节点。
- C2 定义层（L0）：`FeatureDefinition` 构造后不可变；RAW 特征必须声明 entityScopes 且不得携带表达式，DERIVED 特征必须有表达式。所有校验在构造器内完成，校验失败直接抛异常，不产出半成品定义。
- C3 逻辑层构建（L1）：`LogicalDagBuilder` 采用目标驱动，从 targetFeatures 逆向构建可达子图；表达式 AST 只是临时中间表示，构建完成后即丢弃，严禁进入持久化计划模型。
- C4 无环约束：逻辑 DAG 必须无环。构建期用 DFS 三色标记（VISITING/VISITED）检测特征依赖环，构建完成后拓扑排序再兜底校验一次。
- C5 节点去重与命名：逻辑节点按 canonical 签名合并等价节点（`source|名字`、`literal|类型|值`、`operator|名称|输入`）；节点 ID 遵循前缀规范 `source:`、`literal:`、`operator:`、`feature:`，新增节点类型必须沿用。
- C6 声明与推断一致性：特征的声明类型/值形状/实体域必须与 DAG 推断结果一致（唯一例外：声明 DOUBLE 允许推断为 INT），不一致时抛 `DagBuildException`。
- C7 逻辑节点不可变：`LogicalNode` 及其实现（`SourceNode`、`LiteralNode`、`OperatorNode`、`FeatureOutputNode`）构造后不可变，依赖关系通过 `NodeInput` 的节点 ID 与端口引用。
- C8 规划层只读：`LogicalDagOptimizer` 只读遍历 DAG，优化事实（引用计数、可达根、融合候选）外置在 `NodePlanningMetadata`，禁止回写或修改逻辑节点本身。
- C9 物理转换（L2）：每个逻辑节点必须且只能产出一个物理输出槽（`slot:N`），物理节点保持逻辑拓扑序；节点融合（如 countIndustry）仅在 ONLINE 环境允许，且被融合的 extract/中间节点必须引用计数为 1 且不是根节点。
- C10 环境相关决策：物理节点的执行阶段/执行模式/缓存策略只能由 `ExecutionEnvironment` 与节点特征（实体域、算子名、值形状）推导，禁止在运行时临时决定；输出特征槽位必须与逻辑根节点一一对应。

## 构建、测试与开发命令

- `mvn clean package`：使用 Java 21 编译，并生成 thin JAR 与包含 Jackson 依赖的 `target/feature-dag-engine-1.0.0-SNAPSHOT-all.jar`。
- `java -jar target/feature-dag-engine-1.0.0-SNAPSHOT-all.jar`：运行已打包的 `DagDemo`；普通不带 `-all` 的 JAR 不包含第三方依赖。
- `./scripts/run-demo.sh`：通过 Maven 编译并运行 `DagDemo`，需要 Bash 环境。
- `./scripts/run-self-test.sh`：编译主代码和测试代码，并通过 `java -ea` 启用断言执行自测试。

开发环境要求 JDK 21 或更高版本。源码运行依赖由 Maven 管理；使用 `-all.jar` 运行 Demo 时不需要额外配置依赖。

## Demo 输入契约

`com.example.featuredag.demo.DagDemo#main` 必须展示真实的公共 API 调用，不应绕过
`FeatureDagEngine.generate` 或使用伪造的算子结果。当前 Demo 使用三天 app 点击计数案例：

- 公共 generate API 的所有输入值均为普通 Java `List`。
- SCALAR 使用单元素 List，例如 auid=["aaaa"]、request_time=[1785549653]。
- SEQUENCE 使用完整元素 List；第一版不执行跨序列 alignment 校验。
- 调用方负责在调用引擎前把 "1|0|1|v2" 等旧协议转换为干净数组。
- 三天计数公共输出为 auid_omnichannel_paid_cnt_3d=[1]。
- 两条序列按时间从近到远排列；示例时间戳单位是秒。
- `request_time - 259200` 是三天前的边界，窗口判断为严格 `timestamp > boundary`。
- 目标特征 `auid_omnichannel_paid_cnt_3d` 应通过 `greater_in_sequence_typed`、`list_index_typed`、
  `find_list_index_typed` 和 `count` 计算，而不是在 Demo 中手工统计。

## 编码风格与命名约定

使用四个空格缩进和 UTF-8 编码，每个文件只声明一个公共顶级类型，并遵循现有的 `com.example.featuredag.<area>` 包结构。类名和枚举名使用 `PascalCase`，方法名和变量名使用 `camelCase`，枚举常量使用 `UPPER_SNAKE_CASE`。领域类型应尽量保持小型、不可变，并在构造器或 Builder 中通过明确的异常校验输入。沿用 `LogicalDag`、`PhysicalPlan`、`ExecutionContext` 等架构术语。项目未配置格式化或静态检查工具，因此应遵循相邻代码的风格并使用显式导入。

- 注解约定：层间转换点（L0 映射、L1 构建、规划分析、L2 转换、运行时执行）以中文注解说明转换语义，并引用约束编号（C1–C10）；核心链路不引入运行时日志（LOGGER），转换过程信息通过注解与各层产物类型呈现。

## 测试指南

当前测试使用 Java `assert`，而非 JUnit。端到端测试应添加到 `DagEngineSelfTest`，使用确定性测试数据，并为不直观的断言提供清晰的失败消息。修改相关模块时，应覆盖逻辑依赖选择、计划器转换、在线/离线行为和运行时输出。提交前始终运行 `./scripts/run-self-test.sh` 或等价的 `java -ea` 命令。注意：`mvn package` 会编译测试源码，但不会执行该自测试程序；需要显式运行 `DagEngineSelfTest`。

## 提交与拉取请求规范

提交标题应简短、使用祈使语气，例如 `Add cycle detection coverage`，并确保每次提交只聚焦一个主题。拉取请求应说明行为变化及受影响的架构层、列出已运行的验证命令，并关联相关 Issue。若执行行为发生变化，请附上示例计划或控制台输出；本项目没有 UI，通常无需截图。
