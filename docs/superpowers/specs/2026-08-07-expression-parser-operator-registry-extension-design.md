# ExpressionParser 与 OperatorRegistry 扩展设计

## 目标

扩展表达式 DAG 引擎，使附件列出的 32 个业务算子表达式都能完成语法解析、逻辑构图和算子查找，并修复整数被解析为 `Double` 的问题。现有示例算子和离线、在线执行行为必须保持兼容。

## 范围

本次实现包括：

- 数组字面量，例如 `[1, 10, 100]`。
- 柯里化调用参数合并，例如 `slice_v3_typed({"start": 4})(seq)`。
- 纯数字函数名，例如 `64(action_types)`。
- 整数字面量保持为 `Integer`，小数字面量保持为 `Double`。
- AST、逻辑构图及配置依赖扫描对数组字面量的支持。
- 注册附件中的全部 32 个业务算子，并把已有 `add` 扩展为变长参数。
- 简单标量算子的运行时实现，以及序列算子的显式未实现行为。
- Parser、Registry、嵌套业务表达式、错误边界和现有端到端行为的回归测试。

本次不实现序列算子的完整执行语义、`discrete_key` 表查找或中文逗号转换。

## 解析与 AST 设计

`ExpressionParser` 增加左右方括号 token，并在表达式入口解析数组。新增不可变的 `AstArrayLiteral`，保存元素列表及源码范围，并加入 `AstNode` 的 sealed permits 列表。

Lexer 在把数字识别为数值之前向前扫描连续数字；若其后紧邻左括号，则将该段识别为函数标识符。普通数字仍走数值 token。整数与小数使用显式 `if/else` 分支转换，避免 Java 条件表达式造成数值提升。

函数调用解析完成一组参数后，只要下一个 token 仍是左括号，就继续解析下一组参数并追加到同一个 `AstCall.arguments` 列表。对象字面量仍作为普通位置参数，不引入命名参数模型。

## 构图与依赖扫描

`LogicalDagBuilder.buildAst()` 将 `AstArrayLiteral` 递归转换为不可变或稳定顺序的 `List<Object>`，再创建 OBJECT 类型的 literal node。`toLiteralValue()` 同样支持数组嵌套，确保对象与数组可互相嵌套。

`FeatureConfigMapper.collectFeatureReferences()` 递归遍历数组元素，防止数组内出现特征引用时漏掉依赖校验。虽然当前业务数组主要是字面量，该行为保持 AST 语义完整。

## 算子注册设计

`OperatorRegistry.standard()` 保留现有公共入口，并通过同文件内的分类辅助方法注册新增算子，以控制方法体大小。分类包括序列选择与索引、类型或值转换、标量计算和 ops_list 算子。

每个算子的参数个数、输出 `DataType` 和 `ValueShape` 按附件表定义，实体范围使用输入范围并集。透传算子从语义输入推导类型和形状；配置对象参数不改变结果形状。

`sub`、`sign`、`div_num`、`round`、`log_base` 和 `calc_delta_seq` 实现附件要求的简单计算逻辑。`add` 接受两个及以上参数并遍历求和。依赖尚不存在的序列内部操作的 evaluator 统一抛出包含算子名的 `UnsupportedOperationException`，避免产生看似成功但错误的结果。

## 错误处理

数组缺少右方括号、调用参数缺失或分隔符错误时，继续使用 Parser 现有的带 offset 异常格式。算子参数个数仍由 `validateArity` 统一检查。未实现的序列执行路径在运行期给出明确的算子名，而解析、注册和逻辑构图阶段正常通过。

## 测试与验收

在 `DagEngineSelfTest` 中增加以下覆盖：

- 数组、柯里化、数字函数名和整数类型解析。
- 32 个算子的 `require()` 与最小表达式构图/推导。
- hp1h_imp_hpd 的深层嵌套表达式解析和共享子表达式去重。
- 未闭合数组、错误逗号等异常输入。
- 原有七个算子以及现有离线、在线示例回归。

最终执行 `./scripts/run-self-test.sh` 和 `mvn package`。两条命令均成功且没有破坏既有断言，视为实现完成。
