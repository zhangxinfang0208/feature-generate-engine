# 仓库贡献指南

## 项目结构与模块组织

本项目是一个基于 Java 21 的三层特征表达式 DAG 引擎参考实现。生产代码位于 `src/main/java/com/example/featuredag/`，并按职责划分：`definition` 和 `expression` 定义输入与表达式；`logical` 构建逻辑 DAG；`planning` 和 `physical` 完成优化及物理计划转换；`runtime` 执行计划；`operator` 提供算子行为。`demo` 仅用于可运行示例，不应承载核心抽象。无外部依赖的集成测试位于 `src/test/java/com/example/featuredag/DagEngineSelfTest.java`。辅助脚本存放在 `scripts/`，编译产物和 JAR 文件统一写入 `target/`。

## 构建、测试与开发命令

- `mvn clean package`：使用 Java 21 编译，并生成 `target/feature-dag-engine-1.0.0-SNAPSHOT.jar`。
- `java -jar target/feature-dag-engine-1.0.0-SNAPSHOT.jar`：运行已打包的演示程序。
- `./scripts/run-demo.sh`：通过 `javac` 直接编译生产代码并运行 `DagDemo`，需要 Bash 环境。
- `./scripts/run-self-test.sh`：编译主代码和测试代码，并通过 `java -ea` 启用断言执行自测试。

开发环境要求 JDK 21 或更高版本。项目没有外部运行时依赖。

## 编码风格与命名约定

使用四个空格缩进和 UTF-8 编码，每个文件只声明一个公共顶级类型，并遵循现有的 `com.example.featuredag.<area>` 包结构。类名和枚举名使用 `PascalCase`，方法名和变量名使用 `camelCase`，枚举常量使用 `UPPER_SNAKE_CASE`。领域类型应尽量保持小型、不可变，并在构造器或 Builder 中通过明确的异常校验输入。沿用 `LogicalDag`、`PhysicalPlan`、`ExecutionContext` 等架构术语。项目未配置格式化或静态检查工具，因此应遵循相邻代码的风格并使用显式导入。

## 测试指南

当前测试使用 Java `assert`，而非 JUnit。端到端测试应添加到 `DagEngineSelfTest`，使用确定性测试数据，并为不直观的断言提供清晰的失败消息。修改相关模块时，应覆盖逻辑依赖选择、计划器转换、在线/离线行为和运行时输出。提交前始终运行 `./scripts/run-self-test.sh`。注意：`mvn package` 会编译测试源码，但不会执行该自测试程序。

## 提交与拉取请求规范

当前工作副本不包含 Git 历史，因此无法推断仓库既有的提交约定。提交标题应简短、使用祈使语气，例如 `Add cycle detection coverage`，并确保每次提交只聚焦一个主题。拉取请求应说明行为变化及受影响的架构层、列出已运行的验证命令，并关联相关 Issue。若执行行为发生变化，请附上示例计划或控制台输出；本项目没有 UI，通常无需截图。
