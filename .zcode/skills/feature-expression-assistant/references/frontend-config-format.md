# 前台特征配置格式与补全块生成

业务从前台界面只能导出 `features` 数组 JSON（字段是前台自己的格式，不是引擎的
FeatureConfig）。本文说明哪些字段有用、类型如何映射、以及「新增属性」补全块的生成规则。
第 2、3 步使用本文。

## 前台 features JSON 字段说明

业务贴来的每条特征配置大致包含以下字段，**只有前三组参与本流程**，其余忽略：

| 字段 | 用途 | 缺失判定 |
|---|---|---|
| `name` | 特征名，与表达式引用**精确匹配**的匹配键 | 必有（前台入口字段） |
| `type` | 数据类型，如 `"DOUBLE"`、`"STRING"`、`"BIGINT"` | `""` 空串或缺失 = 缺失 |
| `seq_max_length` | 形状：`1` = 标量，`>1` = 序列（同时是截断长度） | `0`、缺失 = 缺失 |
| `dft` | 缺失默认值，如 `"missing"`、`"0"` | 可选，缺失时提示建议补充 |
| `feature_type` | `dense` / `sparse`（参考信息，不参与校验） | — |
| `encode` | `autoHash` / `pureDense` 等（参考信息） | — |
| `prim_keys` | 实体键如 `"gender_new_dev,slot_id"`（参考：非空可视为已声明实体域） | — |
| 其余（`catalog`、`order`、`is_train_feature`、`tableType`、`featureCategory`、`store_name`、`raw_name`、`feature_group_name`…） | 与本流程无关，忽略 | — |

`sparse` 特征的 `type` 常为空串：这类特征通常是字符串/离散语义，但**不要自行替业务拍板**，
列入待补充问题。

## 类型映射表

前台 `type` 值 ↔ 引擎 DataType ↔ 补全块 `data_type` 枚举（业务侧取值，与仓库类型对齐）：

| 前台 type | 引擎 DataType | data_type 枚举 |
|---|---|---|
| `INT` / `INTEGER` | INT | NUMBER |
| `BIGINT` / `LONG` | BIGINT | BIGINT |
| `FLOAT` / `DOUBLE` | DOUBLE | NUMBER |
| `STRING` / `VARCHAR` / `TEXT` | STRING | STRING |
| `BOOLEAN` / `BOOL` | BOOLEAN | BOOLEAN |
| `""` / 缺失 / 其他值 | 缺失或未知 → 需补充/确认 | — |

`data_type` 只有四种取值：`NUMBER`、`BIGINT`、`STRING`、`BOOLEAN`
（引擎 INT/DOUBLE 归入 NUMBER）。

## base 特征必备字段清单（第 3 步差集比对基准）

对每个 base 特征逐一检查：

1. `type` —— 必须。空串/缺失 → 补全块。
2. `seq_max_length` —— 必须。`0`/缺失 → 补全块；有值时校验与表达式用法一致
   （该做序列的地方是 1、该做标量的地方 >1 都算不一致，报告给业务）。
3. `dft` —— 建议有。**业务给了默认值**才生成 `required: "false"` 的补全块；
   没给值时不生成空块，列入待确认问题，不要替业务定默认值。

## 从表达式用法推断缺失值（仅在能唯一确定时使用）

- 需要序列的位置（`zip_concat` 值参数、`slice_by_indices` 参数0、`find_indices` 参数0、
  `get_seq_length`/`count_distinct` 唯一参数、`list_concat` 参数0/1）→ `seq_max_length` 必为
  **大于 1**；但具体数值（序列长度上限）**必须问业务**，不要替业务定。
- 需要标量的位置（`find_indices` 参数1、`calc_delta_seq` 参数1、四则/`min`/`max`/`concat` 参数、
  `discrete`/`log_base` 参数）→ `seq_max_length` = 1。
- `calc_delta_seq` 参数1（base，时间戳类）→ 通常 `BIGINT` 或数值；值类型不确定时问业务。
- 元素类型无法从表达式唯一确定（如 `slice_by_indices` 参数0 的元素类型、
  `zip_concat` 各序列元素类型）→ 列入待补充问题，**不要猜**。

所有推断值在输出时明确标注「推断值，请确认」。

**类型冲突立即报告**：已知的类型信息（业务说明的或配置里读到的）与算子入参约束冲突时
（约束表见 [operator-signatures.md](operator-signatures.md)），当场报告并给修复建议，
不要等第 4 步。典型场景：STRING 序列作为 `calc_delta_seq` 参数0——引擎不做字符串到
数值的隐式转换，字符串形式的时间戳须先套 `to_bigint`（或 `to_int`）再参与计算；
二选一由业务确认：改特征真实类型，或改表达式加显式转换。

## 「新增属性」补全块生成规则

前台「添加属性」表单的固定五字段格式，业务会整块复制到前台：

```json
{
  "raw_name": "seq_max_length",
  "data_value": "1",
  "data_type": "NUMBER",
  "default_value": "",
  "required": "true"
}
```

字段含义：

- `raw_name`：要新增的属性 key（如 `type`、`seq_max_length`、`dft`）。
- `data_value`：属性值，**一律写成字符串**（前台会按 `data_type` 转回 `seq_max_length: 1`）。
- `data_type`：**属性值本身**的类型，不是特征的数据类型。按值的形态判断：
  数字值（`seq_max_length` 的 `50`、`dft` 的 `0`）用 `NUMBER`；
  字符串值（如 `type` 的值 `"BIGINT"`）用 `STRING`；布尔值用 `BOOLEAN`。
  仅当属性值本身就是长整数语义时才用 `BIGINT`。
- `default_value`：默认值，未设置为 `""`。
- `required`：`"true"` / `"false"`（字符串）。

示例：特征 `auid_hwdsp_clk_ts_seq_time_365d` 缺 `type` 与 `seq_max_length`，业务确认其为
BIGINT、长度上限 50 的序列后，输出两个块：

```json
{
  "raw_name": "type",
  "data_value": "BIGINT",
  "data_type": "STRING",
  "default_value": "",
  "required": "true"
}
```

```json
{
  "raw_name": "seq_max_length",
  "data_value": "50",
  "data_type": "NUMBER",
  "default_value": "",
  "required": "true"
}
```

注意第一个块：`type` 的**值** `"BIGINT"` 是字符串，所以 `data_type` 是 `STRING`。

输出时按特征分组：特征名 + 一句话说明（该特征在表达式中的角色）+ 该特征全部属性块，
并明确告知业务「复制到前台该特征的添加属性处」。
