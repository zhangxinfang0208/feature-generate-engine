# 标准算子签名与静态校验规则

21 个标准算子的参数个数、入参类型/形状、配置对象和输出推断规则，全部核对自引擎源码
（`src/main/java/com/example/featuredag/operator/builtin/`）。第 1 步解释脚本报错、
第 4 步终检逐算子核对时使用本表。

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

| 算子 | 参数个数 | 入参要求 | 配置对象（对象字面量） | 输出 |
|---|---|---|---|---|
| `discrete` | 2..2 | 参数0：数值标量；参数1：数组字面量，数值严格递增 | — | INT 标量 |
| `log_base` | 3..3 | value、base、upbound 均数值标量；base>0 且 ≠1，value>0，upbound>0（运行期校验） | — | DOUBLE 标量 |
| `slice_by_indices` | 2..2 | 参数0：任意类型**序列**；参数1：非负整数索引**序列**（越界/负数运行期报错） | — | 透传参数0：同类型同形状序列 |
| `find_indices` | 2..2 | 参数0：**序列**；参数1：标量目标值（equals 匹配，类型需与元素一致） | — | INT 序列 |
| `find_indices_any` | 2..2 | 两个参数都必须是**序列**（参数1为目标值集合，集合语义）；命名参数 `sequence` / `targets` | — | INT 序列 |
| `get_seq_length` | 1..1 | 序列 | — | INT 标量 |
| `count_distinct` | 1..1 | 序列 | — | INT 标量 |
| `zip_concat` | ≥2 | ≥2 个**等长序列**（长度不等运行期报错）；EVENT_SEQUENCE 拒绝 | 末尾可选 `{"delimiter": "#"}` | STRING 序列（逐位拼接） |
| `concat` | ≥2 | ≥2 个**标量**（序列、对象拒绝） | 末尾可选 `{"delimiter": "#"}` | STRING 标量 |
| `list_concat` | 2..3 | 参数0、1 均为**序列**，参数1 非空（首元素广播）；EVENT_SEQUENCE 拒绝 | 第 3 参可选 `{"delimiter": "#"}` | STRING 序列 |
| `hit` | 2..2 | 参数0：EVENT_SEQUENCE 或 Map 序列（元素含 `key`）；参数1：STRING 标量或序列；命名参数 `seq_kv` / `seq_key` | — | 透传参数0 |
| `group_count_concat` | 1..2 | 参数0：非事件**序列** | 第 2 参可选 `{"delimiter":"#", "order":"FIRST_OCCURRENCE"}`，`order` ∈ `FIRST_OCCURRENCE`（默认）/ `COUNT_DESC` | STRING 序列（`值#次数`） |
| `calc_delta_seq` | 2..3 | 参数0：数值**序列**（EVENT_SEQUENCE 拒绝）；参数1：数值标量 base | 第 3 参 `{"direction":"BASE_MINUS_ELEMENT","divisor":60,"need_ceil":0}`：`direction` ∈ `ELEMENT_MINUS_BASE` / `BASE_MINUS_ELEMENT`（默认）；`divisor` 数值 >0（默认 1）；`need_ceil` 0/1（默认 0）。**未知键引擎直接报错** | DOUBLE 序列（与输入等长） |
| `to_int` | 1..1 | 数值或数字字符串；标量→标量，序列→序列 | — | INT（截断取整，溢出运行期报错） |
| `to_bigint` | 1..1 | 同 `to_int` | — | BIGINT |
| `min` / `max` | ≥2 | 全部数值**标量**；EVENT_SEQUENCE 拒绝 | — | 数值提升类型标量 |
| `add` / `sub` / `mul` | 2..2 | 数值标量；命名参数 `value` / `addend`（add）、`margin`（sub）、`multiplier`（mul） | — | 数值提升类型标量 |
| `div` | 2..2 | 数值标量；命名参数 `value` / `divisor` | — | 恒为 DOUBLE 标量（除 0 返回 0.0） |

对象字面量作为配置对象的位置是受限的：`zip_concat`/`concat` 只能出现在最后一个参数，
`list_concat`/`calc_delta_seq` 只能是第 3 参，`group_count_concat` 只能是第 2 参；
其余算子不接受对象字面量参数。

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

**非标准算子**：表达式中出现 21 个标准算子之外的名称时，先按拼写错误处理（脚本会给最近建议）；
确认不是拼写错误后询问业务该算子是否已通过扩展入口注册，未注册则无法执行。
