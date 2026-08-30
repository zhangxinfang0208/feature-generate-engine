# 标准算子签名与静态校验规则

标准算子的参数个数、入参类型/形状、配置对象和输出推断规则，**唯一维护点是
[../operators.json](../operators.json)**（核对自引擎源码
`src/main/java/com/example/featuredag/operator/builtin/`）。第 1 步解释脚本报错、
第 4 步终检逐算子核对时读该文件；本文只保留语法、类型体系与终检方法。

## 表达式语法速查（镜像 ExpressionParser）

- 纯函数调用语法：`算子(参数, 参数, ...)`，可任意嵌套；没有中缀运算符。
- 字符串：`'x'` 或 `"x"`，转义仅支持 `\n \r \t \\ \" \'`。
- 数字：整数（超出 int 自动按 BIGINT 处理）、小数（DOUBLE）、`-` 开头负数；不支持科学计数法。
- 布尔与空：`true` / `false` / `null`。
- 数组字面量 `[0, 10, 100]`；对象字面量 `{"delimiter":"#"}`，键可带引号或裸写，键重复报错，不允许尾逗号。
- 命名参数 `名称=值`：仅 7 个算子支持（见下表），位置参数不能出现在命名参数之后，名称不能重复。
- 标识符：字母或 `_` 开头，可含数字、`_`、`.`；**裸标识符即特征引用**（base 或派生）。
  引擎没有内置请求变量——`timestamp_s` 这类标识符同样是 base 特征，必须配置。
- 全角标点（`，`（）《》"" 等）一律非法，常见于中文输入法未切换。

## 类型与形状体系

- 引擎 DataType：`INT`、`BIGINT`、`DOUBLE`、`STRING`、`BOOLEAN`、`OBJECT`、`EVENT_SEQUENCE`。
- 形状：`SCALAR`（标量）/ `SEQUENCE`（序列）。业务配置里由 `seq_max_length` 表达：`1` = 标量，`>1` = 序列。
- 数值提升链：`INT → BIGINT → DOUBLE`（多入参取上界）。
- `EVENT_SEQUENCE`（事件序列）：元素是带 `key` 字段的 Map，仅事件类场景使用（`hit`）。

## 算子签名表

逐算子的参数个数、入参要求、配置对象与输出见 [../operators.json](../operators.json)，
本文件不再维护重复表格。扩展算子的同步方法：

1. 引擎侧按仓库 AGENTS.md 完成（独立 `.java`、`InitialBusinessOperators` 清单、
   JUnit 4 注册测试与算子测试）。
2. skill 侧只在 `operators.json` 增加一个条目：`min_args`/`max_args`（null 表示无上限）、
   `named_params`（与引擎 `parameterNames` 一致）、`config`（对象字面量的位置 `position`
   与合法键 `keys`，`strict: true` 表示引擎对未知键直接报错）、`input`/`output`
   （中文说明，终检推导用）。`check_expression.py` 启动时自动加载，无需改脚本。
3. 取值规则 `type`：`string`、`positive_number`、`enum` + `values`。
   无法用规则表达的特校验（如 `discrete` 边界数组严格递增）在脚本内实现。
4. 验证：用新算子写一条表达式跑 `check_expression.py`，确认 exit 0 且终检可推导。

对象字面量作为配置对象的位置由各算子 `config.position` 表达（`"last"` 或下标数组）；
没有 `config` 的算子不接受对象字面量参数。

## 第 4 步静态终检方法

自底向上推导：从每个 base 特征的配置（`type` + `seq_max_length`，见
[frontend-config-format.md](frontend-config-format.md)）得出其 (引擎类型, 形状)，
沿表达式树逐算子套用上表，检查每层入参约束并推导输出 (类型, 形状)，直到顶层。

1. **引用完整性**：表达式中每个裸标识符都有对应特征配置，无未定义引用。
2. **形状检查**：需要序列的位置（`find_indices` 参数0、`zip_concat` 各值参数、
   `slice_by_indices` 参数0、`get_seq_length`/`count_distinct` 唯一参数等）对应特征必须
   `seq_max_length > 1`；需要标量的位置（`find_indices` 参数1、四则/聚合参数、`concat` 参数、
   `calc_delta_seq` 参数1）必须 `seq_max_length == 1`。注意中间结果形状：
   `find_indices(...)` 产出 INT 序列，可作为 `slice_by_indices` 的索引参数；
   `slice_by_indices(...)` 透传其参数0 的形状。
3. **类型检查**：数值算子入参须为 INT/BIGINT/DOUBLE（`to_int`/`to_bigint` 额外接受数字字符串）；
   `zip_concat`/`concat` 各元素任意类型（拼接时转字符串）。
4. **输出摘要**：给业务报告顶层输出类型与形状（如「STRING 序列，分隔符 #」）。

**静态不判、仅提示不阻塞**（运行期行为）：`slice_by_indices` 索引非负且不越界、
`zip_concat`/`list_concat` 序列等长与非空、`find_indices` 目标值类型与元素一致、
`log_base`/`div`/`discrete` 的值域、`to_int` 溢出。

**非标准算子**：表达式中出现标准算子（清单见 [../operators.json](../operators.json)）之外的
名称时，先按拼写错误处理（脚本会给最近建议）；确认不是拼写错误后询问业务该算子是否已
通过扩展入口注册，未注册则无法执行。
