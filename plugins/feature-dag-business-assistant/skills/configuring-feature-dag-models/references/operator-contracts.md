# Current Operator Semantic Contracts

本目录用于 DERIVED 语义补全，不是注册表审计或完整性结论。类型、形状、实体域及 `seq_max_length` 的含义以 [feature-fields.md](feature-fields.md) 为准。下文的“构造期”只描述当前 `infer` 实际执行的检查；“可靠业务配置”是为了让运行期求值稳定成立的输入要求，不表示引擎当前一定在构造期拒绝其他声明。

所有长度公式都是安全上界。`M0`、`M1` 等表示相应序列输入已知的正 `seq_max_length`；若公式所需的任一最大长度未知，向业务询问，不猜测数值。标量输出的 `seq_max_length` 为 `1`。

### 长度输出闸门

对包含 `slice_by_indices` 或 `zip_concat` 的 DERIVED 表达式，只有在以下条件同时满足时才可把推导出的上限交给前台：

1. 所有决定源序列长度、索引长度及索引合法性的可达输入都已提供正的 `seq_max_length`；
2. 这些输入的 `value_shape` 均已明确为所需的 `SEQUENCE`/`SCALAR`，并且实体域、类型和形状没有冲突；
3. 每个 `slice_by_indices` 的索引来源（例如 `find_indices` 的被搜索序列）也满足上述完整性；
4. `zip_concat` 的每条参与序列都满足等长运行时约束，且共享上界可以由已知输入安全计算。

否则将 `seq_max_length` 列入“待确认事实”，不生成该字段的新增属性。任何单个已知长度（例如 `365`）都不能替代其他未知或冲突输入，也不能作为整个衍生结果的猜测值。

## `discrete`

- **Signature and arity:** `discrete(value, boundaries)`，恰好 2 个参数。
- **Static input type/shape constraints:** 构造期不检查输入类型或形状。可靠业务配置使用数值 `SCALAR` 的 `value`，以及有限数值组成的 `SEQUENCE` 边界。
- **Output type and shape:** 固定推断为 `INT` / `SCALAR`；桶号从 `0` 开始，值等于边界时进入右侧桶。
- **Entity-scope propagation:** 所有输入实体域的并集。
- **`seq_max_length` propagation:** `1`。
- **Runtime-only checks:** `value` 必须是有限 `Number`；`boundaries` 必须是 `List`，每项为有限数值且严格递增。空边界列表合法并返回 `0`。
- **Configuration-object keys, when present:** 无；第二参数是边界序列，不是配置对象。

## `log_base`

- **Signature and arity:** `log_base(value, base, upbound)`，恰好 3 个参数。
- **Static input type/shape constraints:** 构造期不检查输入类型或形状。可靠业务配置让三个参数都为数值 `SCALAR`。
- **Output type and shape:** 固定推断为 `DOUBLE` / `SCALAR`，计算 `log(min(value, upbound)) / log(base)`。
- **Entity-scope propagation:** 所有输入实体域的并集。
- **`seq_max_length` propagation:** `1`。
- **Runtime-only checks:** 三个值都必须是有限 `Number`；`value > 0`、`upbound > 0`、`base > 0` 且 `base != 1`。
- **Configuration-object keys, when present:** 无。

## `slice_by_indices`

- **Signature and arity:** `slice_by_indices(sequence, indices)`，恰好 2 个参数。
- **Static input type/shape constraints:** 构造期不检查两参数的类型或形状，并直接继承第一参数的类型和形状。可靠业务配置要求第一参数为 `SEQUENCE`，第二参数为整数下标 `SEQUENCE`。
- **Output type and shape:** 类型和形状继承第一参数；可靠配置下为同元素类型的 `SEQUENCE`，按 `indices` 的顺序输出并保留重复下标。
- **Entity-scope propagation:** 两个输入实体域的并集，不仅继承第一输入的实体域。
- **`seq_max_length` propagation:** 输出长度等于运行时 `indices` 长度，安全上界为 `M1`；未知时询问业务。它不受第一序列最大长度约束，因为下标可重复。
- **Runtime-only checks:** 两参数都必须是 `OperatorSequence` 或 `List`；每个下标必须是可精确表示的非负整数且小于源序列当次长度，小数、非有限值、溢出和越界均失败。
- **Configuration-object keys, when present:** 无。

## `find_indices`

- **Signature and arity:** `find_indices(sequence, target)`，恰好 2 个参数。
- **Static input type/shape constraints:** 构造期不检查输入类型或形状。可靠业务配置让第一参数为 `SEQUENCE`，并让 `target` 与其元素具有可比较的业务含义。
- **Output type and shape:** 固定推断为 `INT` / `SEQUENCE`；返回所有满足 `Objects.equals(element, target)` 的逻辑下标，未命中返回空序列。
- **Entity-scope propagation:** 两个输入实体域的并集。
- **`seq_max_length` propagation:** 至多为被搜索序列长度，安全上界为 `M0`；未知时询问业务。
- **Runtime-only checks:** 第一参数必须是 `OperatorSequence` 或 `List`。目标值和序列元素允许为 `null`；运行时不额外校验两者声明类型一致。
- **Configuration-object keys, when present:** 无。

## `find_indices_any`

- **Signature and arity:** `find_indices_any(sequence, targets)`，恰好 2 个参数；这两个名字也是可用的命名参数。
- **Static input type/shape constraints:** 构造期要求两个输入都为 `SEQUENCE`，不检查元素 `DataType`。可靠业务配置让 `targets` 与源序列元素具有兼容的相等语义。
- **Output type and shape:** 固定推断为 `INT` / `SEQUENCE`；返回源序列中命中任一目标值的位置，并保持源序列顺序。
- **Entity-scope propagation:** 两个输入实体域的并集。
- **`seq_max_length` propagation:** 至多为源序列长度，安全上界为 `M0`；未知时询问业务。
- **Runtime-only checks:** 两参数都必须是 `OperatorSequence` 或 `List`；目标集合和元素使用集合的 `equals` / `hashCode` 语义，`null` 可参与匹配。
- **Configuration-object keys, when present:** 无。

## `get_seq_length`

- **Signature and arity:** `get_seq_length(sequence)`，恰好 1 个参数。
- **Static input type/shape constraints:** 构造期不检查类型或形状。可靠业务配置使用 `SEQUENCE`。
- **Output type and shape:** 固定推断为 `INT` / `SCALAR`，返回当次序列长度。
- **Entity-scope propagation:** 输入实体域原样构成并集。
- **`seq_max_length` propagation:** `1`。
- **Runtime-only checks:** 值必须是 `OperatorSequence`、`Collection` 或 Java 数组；其他值（含 `null`）失败。
- **Configuration-object keys, when present:** 无。

## `count_distinct`

- **Signature and arity:** `count_distinct(sequence)`，恰好 1 个参数。
- **Static input type/shape constraints:** 构造期不检查类型或形状。可靠业务配置使用 `SEQUENCE`。
- **Output type and shape:** 固定推断为 `INT` / `SCALAR`；按元素的 `equals` / `hashCode` 语义去重后返回基数，`null` 可作为一个不同值。
- **Entity-scope propagation:** 输入实体域原样构成并集。
- **`seq_max_length` propagation:** `1`。
- **Runtime-only checks:** 值必须是 `OperatorSequence`、`Collection` 或 Java 数组；其他值（含 `null`）失败。
- **Configuration-object keys, when present:** 无。

## `zip_concat`

- **Signature and arity:** `zip_concat(sequence0, sequence1, ..., [config])`，总参数 2 个及以上；只有末尾运行时值为对象时才把它解释为配置，因此可靠配置至少提供两条序列，另可加一个配置对象。
- **Static input type/shape constraints:** 构造期仅拒绝任一输入声明为 `EVENT_SEQUENCE`，不检查 `SEQUENCE` 形状，也不静态识别末尾配置。可靠业务配置让所有值参数均为非事件 `SEQUENCE`，末尾可选参数为对象。
- **Output type and shape:** 固定推断为 `STRING` / `SEQUENCE`；逐位置把各序列元素转为字符串并拼接。
- **Entity-scope propagation:** 包括可选配置在内的所有输入实体域并集。
- **`seq_max_length` propagation:** 运行时要求各值序列等长。若它们共享同一已知最大值 `M`，输出上界为 `M`；若已知上界不相同，`min(M0, M1, ...)` 仍是安全上界；任一所需上界未知时询问业务。
- **Runtime-only checks:** 去掉末尾 `Map` 后必须仍有至少两条序列；每条值参数必须是 `OperatorSequence` 或 `List` 且当次长度完全相等；元素为 `Map` 时失败，其他元素（含 `null`）使用 `String.valueOf`。
- **Configuration-object keys, when present:** `delimiter`，缺失或值为 `null` 时默认 `"#"`，其他值用 `String.valueOf`；未知键当前被忽略。

## `concat`

- **Signature and arity:** `concat(value0, value1, ..., [config])`，总参数 2 个及以上；末尾 `OBJECT` 形状输入在构造期作为配置，不计入值参数，值参数仍须至少两个。
- **Static input type/shape constraints:** 构造期要求每个值参数是 `SCALAR`，且类型不是 `OBJECT` 或 `EVENT_SEQUENCE`；末尾配置只按 `OBJECT` 形状识别。可靠业务配置让末尾配置实际为对象。
- **Output type and shape:** 固定推断为 `STRING` / `SCALAR`，按参数顺序拼接标量的字符串形式。
- **Entity-scope propagation:** 包括可选配置在内的所有输入实体域并集。
- **`seq_max_length` propagation:** `1`。
- **Runtime-only checks:** 末尾运行时值为 `Map` 时才作为配置；值参数若为 `List`、`OperatorSequence` 或 `Map` 则失败。其他值（含 `null`）使用 `String.valueOf`。
- **Configuration-object keys, when present:** `delimiter`，缺失或值为 `null` 时默认 `"#"`，其他值用 `String.valueOf`；未知键当前被忽略。

## `list_concat`

- **Signature and arity:** `list_concat(sequence, suffix_sequence, [config])`，2 或 3 个参数。
- **Static input type/shape constraints:** 构造期要求前两个输入都是 `SEQUENCE` 且类型不是 `EVENT_SEQUENCE`；第三参数存在时必须同时为 `OBJECT` 类型和 `OBJECT` 形状。
- **Output type and shape:** 固定推断为 `STRING` / `SEQUENCE`；当前实现取 `suffix_sequence` 的首元素，并把它广播拼接到第一序列的每个元素。
- **Entity-scope propagation:** 包括可选配置在内的所有输入实体域并集。
- **`seq_max_length` propagation:** 由两条输入序列最大值相加得到安全上界 `M0 + M1`；当前广播实现的实际输出长度严格等于第一序列当次长度，因此 `M0` 是更紧的已知上界。任一所需最大值未知时询问业务，不猜测。
- **Runtime-only checks:** 两个值参数都必须是 `OperatorSequence` 或 `List`；`suffix_sequence` 不得为空；它的首元素及第一序列任一元素若为 `Map` 则失败，其他值（含 `null`）按字符串形式拼接；第三参数必须实际为 `Map`。
- **Configuration-object keys, when present:** `delimiter`，缺失或值为 `null` 时默认 `"#"`，其他值用 `String.valueOf`；未知键当前被忽略。

## `hit`

- **Signature and arity:** `hit(seq_kv, seq_key)`，恰好 2 个参数；这两个名字也是可用的命名参数。
- **Static input type/shape constraints:** 构造期要求第一输入为 `EVENT_SEQUENCE` / `SEQUENCE`，第二输入为 `STRING` / `SEQUENCE`。
- **Output type and shape:** 继承第一输入，即 `EVENT_SEQUENCE` / `SEQUENCE`；按查询 key 过滤事件，保持源顺序并保留重复事件。
- **Entity-scope propagation:** 两个输入实体域的并集。
- **`seq_max_length` propagation:** 过滤不会增加事件数，安全上界为 `M0`；未知时询问业务。
- **Runtime-only checks:** 两参数都必须是 `OperatorSequence` 或 `List`；查询 key 每项必须为 `String`；每个事件必须为 `Map`，必须含 `key` 字段，且该字段值必须为 `String`。
- **Configuration-object keys, when present:** 无；事件对象要求的 `key` 是事件字段，不是配置键。

## `group_count_concat`

- **Signature and arity:** `group_count_concat(sequence, [config])`，1 或 2 个参数；`sequence`、`config` 也是可用的命名参数。
- **Static input type/shape constraints:** 构造期要求第一输入为 `SEQUENCE` 且类型不是 `EVENT_SEQUENCE`；第二输入存在时只要求形状为 `OBJECT`，不检查其 `DataType`。可靠业务配置让第二输入实际为对象。
- **Output type and shape:** 固定推断为 `STRING` / `SEQUENCE`；每个不同值输出 `value + delimiter + count`。默认按首次出现顺序；计数降序在同频时仍保持首次出现顺序。
- **Entity-scope propagation:** 包括可选配置在内的所有输入实体域并集。
- **`seq_max_length` propagation:** 不同值数量不超过输入元素数，安全上界为 `M0`；未知时询问业务。
- **Runtime-only checks:** 第一参数必须是 `OperatorSequence` 或 `List`；元素为 `Map` 时失败，其他元素按 `equals` / `hashCode` 分组且允许 `null`；第二参数必须实际为 `Map`；`order` 只接受指定枚举值。
- **Configuration-object keys, when present:** `delimiter`（默认 `"#"`，非 `null` 值用 `String.valueOf`）和 `order`（默认 `FIRST_OCCURRENCE`，也可为 `COUNT_DESC`）；未知键当前被忽略。

## `calc_delta_seq`

- **Signature and arity:** `calc_delta_seq(sequence, base, [config])`，2 或 3 个参数。
- **Static input type/shape constraints:** 构造期仅拒绝第一输入类型为 `EVENT_SEQUENCE`，不检查第一输入形状、`base` 的类型/形状或第三输入是否为对象。可靠业务配置使用有限数值 `SEQUENCE`、有限数值 `SCALAR` base 和可选对象配置。
- **Output type and shape:** 固定推断为 `DOUBLE` / `SEQUENCE`；默认逐项计算 `base - element`，再除以 divisor，并可向上取整。
- **Entity-scope propagation:** 包括可选配置在内的所有输入实体域并集。
- **`seq_max_length` propagation:** 保持第一序列的长度，安全上界为 `M0`；未知时询问业务。
- **Runtime-only checks:** 序列在算子边界必须为 `List`，base 和每个元素必须为有限 `Number`，事件 `Map` 元素失败，结果必须有限；配置必须为 `Map`，且未知键失败；`direction` 必须为指定字符串，`divisor` 必须有限且大于 0，`need_ceil` 必须是数值 0 或 1。
- **Configuration-object keys, when present:** `direction`：`BASE_MINUS_ELEMENT`（默认）或 `ELEMENT_MINUS_BASE`；`divisor`：默认 `1.0`；`need_ceil`：数值 `0`（默认）或 `1`。

## `to_int`

- **Signature and arity:** `to_int(value)`，恰好 1 个参数。
- **Static input type/shape constraints:** 构造期要求输入类型为数值类型或 `STRING`，并拒绝 `EVENT_SEQUENCE`；`SEQUENCE` 保持序列形状，`SCALAR` 和 `CANDIDATE_VECTOR` 都推断为 `SCALAR`，其他形状失败。
- **Output type and shape:** 固定元素类型为 `INT`；输入为 `SEQUENCE` 时输出 `SEQUENCE`，输入为 `SCALAR` 或 `CANDIDATE_VECTOR` 时输出 `SCALAR`。
- **Entity-scope propagation:** 输入实体域原样构成并集。
- **`seq_max_length` propagation:** 序列转换逐项保持长度，安全上界为 `M0`；标量输出为 `1`。所需序列上界未知时询问业务。
- **Runtime-only checks:** 每个值必须为有限 `Number` 或可解析的非空十进制字符串；小数部分向零截断；超出 32 位 `int` 范围、非数值字符串和其他类型失败。运行时只有 `OperatorSequence` / `List` 被当作序列。
- **Configuration-object keys, when present:** 无。

## `to_bigint`

- **Signature and arity:** `to_bigint(value)`，恰好 1 个参数。
- **Static input type/shape constraints:** 构造期要求输入类型为数值类型或 `STRING`，并拒绝 `EVENT_SEQUENCE`；`SEQUENCE` 保持序列形状，`SCALAR` 和 `CANDIDATE_VECTOR` 都推断为 `SCALAR`，其他形状失败。
- **Output type and shape:** 固定元素类型为 `BIGINT`；输入为 `SEQUENCE` 时输出 `SEQUENCE`，输入为 `SCALAR` 或 `CANDIDATE_VECTOR` 时输出 `SCALAR`。
- **Entity-scope propagation:** 输入实体域原样构成并集。
- **`seq_max_length` propagation:** 序列转换逐项保持长度，安全上界为 `M0`；标量输出为 `1`。所需序列上界未知时询问业务。
- **Runtime-only checks:** 每个值必须为有限 `Number` 或可解析的非空十进制字符串；小数部分向零截断；超出 64 位 `long` 范围、非数值字符串和其他类型失败。运行时只有 `OperatorSequence` / `List` 被当作序列。
- **Configuration-object keys, when present:** 无。

## `min`

- **Signature and arity:** `min(value0, value1, ...)`，至少 2 个参数。
- **Static input type/shape constraints:** 构造期只拒绝类型为 `EVENT_SEQUENCE` 的输入，不检查其他类型是否数值，也不检查 `SCALAR` 形状。可靠业务配置让所有输入均为数值 `SCALAR`。
- **Output type and shape:** 形状固定为 `SCALAR`；推断类型按 `DOUBLE > BIGINT > INT` 取输入宽度上界（没有 `DOUBLE` / `BIGINT` 时当前实现落为 `INT`）。精确比较，相等时保留最左值。
- **Entity-scope propagation:** 所有输入实体域的并集。
- **`seq_max_length` propagation:** `1`。
- **Runtime-only checks:** 每个值必须是有限 `Number`；返回胜出参数的原运行时数值载体。
- **Configuration-object keys, when present:** 无。

## `max`

- **Signature and arity:** `max(value0, value1, ...)`，至少 2 个参数。
- **Static input type/shape constraints:** 构造期只拒绝类型为 `EVENT_SEQUENCE` 的输入，不检查其他类型是否数值，也不检查 `SCALAR` 形状。可靠业务配置让所有输入均为数值 `SCALAR`。
- **Output type and shape:** 形状固定为 `SCALAR`；推断类型按 `DOUBLE > BIGINT > INT` 取输入宽度上界（没有 `DOUBLE` / `BIGINT` 时当前实现落为 `INT`）。精确比较，相等时保留最左值。
- **Entity-scope propagation:** 所有输入实体域的并集。
- **`seq_max_length` propagation:** `1`。
- **Runtime-only checks:** 每个值必须是有限 `Number`；返回胜出参数的原运行时数值载体。
- **Configuration-object keys, when present:** 无。

## `add`

- **Signature and arity:** `add(value, addend)`，恰好 2 个参数；这两个名字也是可用的命名参数。
- **Static input type/shape constraints:** 构造期只拒绝类型为 `EVENT_SEQUENCE` 的输入，不检查其他类型是否数值，也不检查 `SCALAR` 形状。可靠业务配置让两输入均为数值 `SCALAR`。
- **Output type and shape:** 固定为 `SCALAR`；推断类型按 `DOUBLE > BIGINT > INT` 取宽度上界（没有 `DOUBLE` / `BIGINT` 时当前实现落为 `INT`）。
- **Entity-scope propagation:** 两个输入实体域的并集。
- **`seq_max_length` propagation:** `1`。
- **Runtime-only checks:** 两个值都必须是有限 `Number`；精确十进制相加。双方均为整型载体时返回 `Long` 并要求结果在 64 位范围内；任一为非整型数值载体时返回有限 `Double`。
- **Configuration-object keys, when present:** 无。

## `sub`

- **Signature and arity:** `sub(value, margin)`，恰好 2 个参数；这两个名字也是可用的命名参数，计算 `value - margin`。
- **Static input type/shape constraints:** 构造期只拒绝类型为 `EVENT_SEQUENCE` 的输入，不检查其他类型是否数值，也不检查 `SCALAR` 形状。可靠业务配置让两输入均为数值 `SCALAR`。
- **Output type and shape:** 固定为 `SCALAR`；推断类型按 `DOUBLE > BIGINT > INT` 取宽度上界（没有 `DOUBLE` / `BIGINT` 时当前实现落为 `INT`）。
- **Entity-scope propagation:** 两个输入实体域的并集。
- **`seq_max_length` propagation:** `1`。
- **Runtime-only checks:** 两个值都必须是有限 `Number`；精确十进制相减。双方均为整型载体时返回 `Long` 并要求结果在 64 位范围内；任一为非整型数值载体时返回有限 `Double`。
- **Configuration-object keys, when present:** 无。

## `mul`

- **Signature and arity:** `mul(value, multiplier)`，恰好 2 个参数；这两个名字也是可用的命名参数。
- **Static input type/shape constraints:** 构造期只拒绝类型为 `EVENT_SEQUENCE` 的输入，不检查其他类型是否数值，也不检查 `SCALAR` 形状。可靠业务配置让两输入均为数值 `SCALAR`。
- **Output type and shape:** 固定为 `SCALAR`；推断类型按 `DOUBLE > BIGINT > INT` 取宽度上界（没有 `DOUBLE` / `BIGINT` 时当前实现落为 `INT`）。
- **Entity-scope propagation:** 两个输入实体域的并集。
- **`seq_max_length` propagation:** `1`。
- **Runtime-only checks:** 两个值都必须是有限 `Number`；精确十进制相乘。双方均为整型载体时返回 `Long` 并要求结果在 64 位范围内；任一为非整型数值载体时返回有限 `Double`。
- **Configuration-object keys, when present:** 无。

## `div`

- **Signature and arity:** `div(value, divisor)`，恰好 2 个参数；这两个名字也是可用的命名参数。
- **Static input type/shape constraints:** 构造期只拒绝类型为 `EVENT_SEQUENCE` 的输入，不检查其他类型是否数值，也不检查 `SCALAR` 形状。可靠业务配置让两输入均为数值 `SCALAR`。
- **Output type and shape:** 固定推断为 `DOUBLE` / `SCALAR`。
- **Entity-scope propagation:** 两个输入实体域的并集。
- **`seq_max_length` propagation:** `1`。
- **Runtime-only checks:** 两个值都必须是有限 `Number`；分母为零（含 `-0.0`）时返回 `0.0`；否则以 `DECIMAL64` 精度求商，超出有限 `double` 范围时失败。
- **Configuration-object keys, when present:** 无。
