# `calc_delta_seq` 算子使用说明

## 1. 用途与签名

`calc_delta_seq` 对数值序列逐元素计算差值，输出 `DOUBLE / SEQUENCE`，并保持输入序列的长度和顺序。

支持两种调用形式：

```text
calc_delta_seq(sequence, base)
calc_delta_seq(sequence, base, config)
```

- `sequence`：数值序列；
- `base`：有限数值标量；
- `config`：可选对象，控制减法方向、单位换算和是否向上取整。

两参数调用默认使用 `base - element` 语义：

```text
result[i] = base - sequence[i]
```

例如：

```text
calc_delta_seq([2, 5, 9], 10)
=> [8.0, 5.0, 1.0]
```

## 2. 配置参数

`config` 只接受以下字段：

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `direction` | STRING | `BASE_MINUS_ELEMENT` | 减法方向 |
| `divisor` | NUMBER | `1.0` | 差值的除数，必须是有限正数 |
| `need_ceil` | NUMBER | `0` | 是否对换算结果向上取整，只接受 `0` 或 `1` |

`direction` 支持：

| 值 | 计算公式 |
|---|---|
| `ELEMENT_MINUS_BASE` | `(sequence[i] - base) / divisor` |
| `BASE_MINUS_ELEMENT` | `(base - sequence[i]) / divisor` |

当 `need_ceil=1` 时，算子在除以 `divisor` 后执行 `Math.ceil`；例如 `1.2` 得到
`2.0`，`-1.2` 得到 `-1.0`。取整产生的负零会规范为 `0.0`。

配置对象包含未知字段、非法方向，或者 `divisor <= 0`、`NaN`、无穷大，或者
`need_ceil` 不是数值 `0/1` 时，算子会直接失败。

## 3. 毫秒时间戳的单位换算

当输入是 Unix 毫秒时间戳时，常用除数如下：

| 输出单位 | `divisor` |
|---|---:|
| 毫秒 | `1` |
| 秒 | `1000` |
| 分钟 | `60000` |
| 小时 | `3600000` |
| 天 | `86400000` |

“当前请求时间距离历史行为多久”使用请求时间减行为时间：

```text
calc_delta_seq(
    behavior_timestamp_ms_seq,
    request_timestamp_ms,
    {"direction":"BASE_MINUS_ELEMENT","divisor":3600000}
)
```

等价公式：

```text
timegap_hours[i]
    = (request_timestamp_ms - behavior_timestamp_ms_seq[i]) / 3600000
```

示例数据：

```text
request_timestamp_ms = 1720007200000

behavior_timestamp_ms_seq = [
    1720003600000,
    1719996400000
]
```

计算结果：

```text
[
    (1720007200000 - 1720003600000) / 3600000,
    (1720007200000 - 1719996400000) / 3600000
]
=> [1.0, 3.0]
```

若行为时间晚于请求时间，结果为负数；算子不会自动截断为 `0`。

## 4. 电商点击子序列案例

假设以下输入序列逐位置对齐：

```text
click_item_id_seq =
["item_101", "item_102", "item_103"]

click_creative_industry_seq =
["零售", "游戏", "服饰"]

click_app_category_seq =
["电商", "游戏", "电商"]

click_timestamp_ms_seq =
[1720003600000, 1720000000000, 1719996400000]

request_timestamp_ms = 1720007200000
```

先选择“电商”位置：

```text
click_ecommerce_indices =
    find_indices(click_app_category_seq, "电商")

=> [0, 2]
```

计算所选行为的小时 timegap：

```text
click_ecommerce_timegap_hours =
    calc_delta_seq(
        slice_by_indices(click_timestamp_ms_seq, click_ecommerce_indices),
        request_timestamp_ms,
        {"direction":"BASE_MINUS_ELEMENT","divisor":3600000}
    )

=> [1.0, 3.0]
```

最终逐位置拼接：

```text
zip_concat(
    slice_by_indices(click_item_id_seq, click_ecommerce_indices),
    slice_by_indices(click_creative_industry_seq, click_ecommerce_indices),
    slice_by_indices(click_app_category_seq, click_ecommerce_indices),
    click_ecommerce_timegap_hours,
    {"delimiter":"#"}
)
```

输出：

```text
[
    "item_101#零售#电商#1.0",
    "item_103#服饰#电商#3.0"
]
```

下标特征和 timegap 特征可声明为 `INTERNAL_ONLY`，仅最终拼接特征声明为 `OUTPUT`。

## 5. 边界与执行约束

- 时间戳序列和请求时间必须是有限数值；毫秒时间戳建议在特征配置中声明为 `DOUBLE`。
- 若外部平台只能把请求时间声明为 `STRING`，应在表达式中显式转换，例如
  `calc_delta_seq(timestamp_seq, to_bigint(request_time), {"divisor":60000})`；
  `calc_delta_seq` 本身不会隐式解析字符串。
- `EVENT_SEQUENCE` 不做隐式字段投影；应先提供独立的数值时间戳序列。
- `calc_delta_seq` 只计算差值，不负责365D时间窗过滤。若源数据没有按365D截取，还需要在上游完成时间窗选择或另行提供范围过滤能力。
- Native Batch 在同一 group 内按序列对象身份、`base`、`direction`、`divisor` 和
  `need_ceil` 共同复用结果；不同单位、方向或取整配置不会互相污染。
