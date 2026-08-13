# 泛化事件 Map 模型实现评审

## 1. 评审结论

将固定 5 字段的 `SequenceEvent` 删除，并把事件元素改为
`Map<String, Object>`，整体方向可行，也更符合通用特征 DAG 引擎的定位。

该方案可以实现：

- 事件字段不再被 `itemId`、`industryId`、`eventType` 等业务属性限制；
- 调用方能够传入任意事件属性；
- `SequenceBlock`、`SequenceView` 和通用序列算子不再依赖固定事件 record；
- 公共输出可以保留输入事件的完整属性集合；
- `zip_concat`、`calc_delta_seq` 不再隐式读取事件字段或依赖事件对象的默认
  `toString()`。

当前实现能够正常编译并通过已有自测试，但还存在几项契约和设计问题，不建议在修复前直接作为
“任意属性原样进出”的最终实现合入。

## 2. 目标契约

建议把本次改造的目标明确为：

> `EVENT_SEQUENCE` 的每个事件是一个不可变的 `Map<String, Object>`。输入边界只验证事件必须是
> String key 的 Map，并对 Map/List 等容器进行递归防御性复制；不识别、不改写、不转换任何业务
> 字段。输出边界按照输入字段名、字段值和顺序递归物化。

这里的“原样”具体表示：

- 字段名保持不变；
- 字段迭代顺序保持不变；
- `null` 保持不变；
- 数字不主动改变 Java 类型；
- 嵌套 Map/List 的结构和内容保持不变；
- 输出是不可变副本，不承诺与输入对象具有相同引用；
- 不再自动执行 `item_id -> itemId` 等字段别名转换；
- 不再因为字段名是 `timestamp` 就强制转换为 `long`。

字段提取、别名、类型校验和查询 key 归一化属于字段访问器职责，不属于通用事件输入边界职责。

## 3. 当前实现概况

当前代码已经完成以下改造：

- 删除固定字段的 `SequenceEvent` record；
- `SequenceBlock` 改为保存 `List<Map<String, Object>>`；
- `SequenceValue.elementAt()` 返回事件 Map；
- `SequenceView.filterByIndustry()` 改为 `filterByColumn()`；
- 输入解码允许事件包含固定 5 字段之外的属性；
- 输出物化不再重新构造固定 5 字段 Map；
- `zip_concat` 明确拒绝事件 Map，避免输出 Map 或 record 的默认字符串格式；
- `calc_delta_seq` 明确拒绝事件 Map，不再隐式读取 `value`；
- `supportsSequenceView` 已接入物理计划和运行时输入适配。

这些变化解决了固定 record 限制和 `zip_concat` 输出格式泄漏问题，但当前实现仍保留了部分旧业务
规则，并且没有做到事件值的深度不可变。

## 4. 主要问题

### 4.1 P1：事件没有做到深度不可变

涉及代码：

- `src/main/java/com/example/featuredag/runtime/SequenceBlock.java`

`SequenceBlock.immutableEvent()` 当前只复制事件 Map 的第一层：

```java
copy.put((String) entry.getKey(), entry.getValue());
```

如果事件包含嵌套的 Map、List 或其他可变对象，内部仍保存调用方传入的原始引用。例如：

```java
List<String> tags = new ArrayList<>();
tags.add("hot");

Map<String, Object> event = new LinkedHashMap<>();
event.put("tags", tags);

SequenceBlock block = new SequenceBlock("events", 1L, List.of(event));
tags.add("changed");
```

此时引擎中的事件内容也会发生变化。这会破坏：

- 不可变值契约；
- `count_distinct` 和 `find_indices` 的稳定结果；
- 已构建索引与事件内容的一致性；
- 同一次执行内缓存结果的正确性；
- 并发读取安全性。

建议新增统一的递归复制函数：

```java
private static Object immutableValue(Object value) {
    if (value instanceof Map<?, ?>) {
        Map<String, Object> copy = new LinkedHashMap<>();
        // 校验 String key，并递归复制 value
        return Collections.unmodifiableMap(copy);
    }
    if (value instanceof List<?>) {
        List<Object> copy = new ArrayList<>();
        // 递归复制元素
        return Collections.unmodifiableList(copy);
    }
    return value;
}
```

还需要明确数组、Set、自定义可变对象是否允许进入事件。第一版建议只递归支持 Map/List，其他对象
按标量处理，并在公共契约中说明调用方不得传入可变业务对象。

### 4.2 P1：当前并非“任意属性原样进出”

涉及代码：

- `src/main/java/com/example/featuredag/api/FeatureInputDecoder.java`

当前输入解码仍包含固定业务字段逻辑：

```text
item_id     -> itemId
industry_id -> industryId
event_type  -> eventType
```

同时，名为 `timestamp` 的字段会被强制校验并转换为 `long`。

因此下面的输入：

```json
{
  "item_id": "item-1",
  "timestamp": 1.0
}
```

输出会变成：

```json
{
  "itemId": "item-1",
  "timestamp": 1
}
```

这属于规范化，不是原样透传。它还会导致：

- 同时存在 `item_id` 和 `itemId` 时丢弃其中一个字段；
- 业务恰好使用名为 `timestamp` 的非整数属性时被拒绝；
- 输入输出无法做到结构对称；
- 通用输入层继续依赖历史事件 Schema。

建议删除 `canonicalFieldName()`、`isStandardAlias()` 和针对 `timestamp` 的特殊处理。输入边界只做：

1. 每个事件必须是 Map；
2. 每个 key 必须是 String；
3. 对值进行递归防御性复制。

如果必须保持旧协议兼容，应把契约改名为“规范化后透传”，不能继续称为“原样输出”。更推荐把
旧字段别名和 timestamp 类型要求迁移到可配置字段访问器中。

### 4.3 P1：索引 key 与查询 key 归一化不对称

涉及代码：

- `src/main/java/com/example/featuredag/runtime/SequenceIndexRegistry.java`
- `src/main/java/com/example/featuredag/runtime/SequenceIndexProvider.java`
- `src/main/java/com/example/featuredag/runtime/SequenceKeyIndex.java`

当前行业索引读取原始事件值：

```java
(block, index) -> block.columnValueAt("industryId", index)
```

查询 key 则执行：

```java
key -> String.valueOf(key)
```

这会产生不对称行为。例如事件中的 `industryId` 是整数 `1`，索引键为 `Integer(1)`，查询键会变成
字符串 `"1"`，查询结果错误地返回 0。

正确做法是让相同的归一化器同时作用于索引字段值和查询 key：

```java
Object rawKey = accessor.read(block, baseIndex);
Object normalizedKey = provider.normalizeKey(rawKey);
```

还应明确 null 和字段缺失语义。推荐区分：

- 字段不存在：默认不进入索引；
- 字段存在但值为 null：是否进入 null 索引由 Provider 决定；
- 查询 key 为 null：不能通过 `String.valueOf(null)` 变成字符串 `"null"`。

### 4.4 P2：`OperatorSequence` 不应承担字段过滤

涉及代码：

- `src/main/java/com/example/featuredag/operator/OperatorSequence.java`
- `src/main/java/com/example/featuredag/runtime/SequenceValue.java`
- `src/main/java/com/example/featuredag/runtime/SequenceView.java`

当前接口为：

```java
public interface OperatorSequence {
    int size();
    Object elementAt(int index);
    OperatorSequence filterByColumn(String column, Object value);
}
```

`filterByColumn()` 虽然比 `filterByIndustry()` 泛化，但仍然假设所有序列元素都是有字段的对象。
数字序列、字符串序列和第三方 `OperatorSequence` 实现都被迫实现一个无意义的方法。

这也和 `supportsSequenceView` 的最小读取协议定位冲突。建议恢复为：

```java
public interface OperatorSequence {
    int size();
    Object elementAt(int index);
}
```

字段过滤应放在 runtime 的通用视图工具中，并通过访问器读取元素：

```java
SequenceView.filter(
        SequenceValue source,
        SequenceFieldAccessor accessor,
        Object expectedValue);
```

这样 operator 层不需要知道事件使用 Map、列名还是其他存储结构，符合 C1/C10。

### 4.5 P2：字段访问器注册机制尚未真正完成

当前 `SequenceIndexRegistry.standard()` 仍直接硬编码：

```java
SequenceKeyDomains.INDUSTRY
block.columnValueAt("industryId", index)
```

这只是把固定的 `industryIds[]` 换成固定的 Map key，核心层仍然知道 `industryId`。

建议把字段读取和索引算法拆开：

```java
@FunctionalInterface
public interface SequenceFieldAccessor {
    Object read(Map<String, Object> event);
}
```

```java
registry.register(
        new SequenceKeyDomain("event.industry"),
        event -> event.get("industryId"),
        normalizer);
```

或者注册字段路径：

```java
registry.registerField(
        new SequenceKeyDomain("event.industry"),
        "industryId",
        normalizer);
```

通用核心只保存 `SequenceKeyDomain -> Provider`，具体字段名由业务初始化模块注册。标准核心注册表不应
默认知道行业字段。

### 4.6 P2：输出物化直接返回内部事件 Map

涉及代码：

- `src/main/java/com/example/featuredag/runtime/ExternalValueMaterializer.java`

当前实现直接把 `SequenceBlock` 中的行 Map 添加到输出 List。顶层 Map 虽不可修改，但嵌套值仍可能是
可变引用，并且公共输出与运行时内部共享同一对象图。

建议输出边界调用递归物化逻辑：

```java
Object materialized = materializeRaw(sequence.elementAt(index));
```

并校验最终结果确实是 `Map<String, Object>`。这样可以隔离公共输出和内部运行时对象。

### 4.7 P2：结构化事件只在求值阶段被拒绝

`zip_concat` 和 `calc_delta_seq` 当前在遍历元素时判断 `element instanceof Map`，因此错误只能在真正执行
到非空事件时暴露。

这会导致：

- 同一个错误配置在空序列时可能不报错；
- 引擎可以初始化成功，但请求执行时才失败；
- Single 和 Batch 的错误时机更难保持一致。

当前 `DataType.EVENT_SEQUENCE` 在逻辑构建期已经可见，因此这两个算子应优先在 `infer()` 阶段拒绝
结构化事件序列，并保留运行时校验作为防御。

## 5. 推荐设计边界

### 5.1 通用序列协议

```java
public interface OperatorSequence {
    int size();
    Object elementAt(int index);
}
```

该协议只表达“可按逻辑下标读取”，不包含任何事件或字段能力。

### 5.2 通用事件存储

```java
public final class SequenceBlock implements SequenceValue {
    private final List<Map<String, Object>> events;

    public Map<String, Object> rowAtBaseIndex(int index);
}
```

构造时递归复制，内部只保存不可变 Map/List。

### 5.3 字段访问器

```java
@FunctionalInterface
public interface SequenceFieldAccessor {
    Object read(Map<String, Object> event);
}
```

字段访问器负责：

- 字段名或嵌套字段路径；
- 别名兼容；
- 缺失字段处理；
- 字段类型校验；
- 索引值规范化。

例如行业字段兼容新旧命名：

```java
event -> event.containsKey("industryId")
        ? event.get("industryId")
        : event.get("industry_id")
```

此逻辑应由业务模块注册，不进入通用输入解码器。

### 5.4 视图过滤

```java
public static SequenceView filter(
        SequenceValue source,
        SequenceFieldAccessor accessor,
        Object expectedValue) {
    // 遍历 source 的逻辑下标，保存匹配的 baseIndex。
}
```

`SequenceView` 只维护 selection，不能重新引入 `industryId` 等固定字段。

### 5.5 索引注册

Provider 应包含访问器和同一套归一化规则：

```java
public interface SequenceIndexProvider {
    SequenceKeyDomain keyDomain();
    SequenceFieldAccessor accessor();
    Object normalizeKey(Object value);
}
```

索引构建和查询都必须调用 `normalizeKey()`。

## 6. 算子语义

### 6.1 `get_seq_length`

只读取序列长度，可以直接消费事件 Map 视图。

### 6.2 `slice_by_indices`

按索引返回事件 Map，不解释字段，可以直接消费事件 Map 视图。

### 6.3 `find_indices`

如果目标也是 Map，则使用 Map 的完整内容相等性。任何额外属性不同都会视为不同事件。需要在文档中
明确这是完整事件匹配，不是按某个字段匹配。

### 6.4 `count_distinct`

对事件序列按整个 Map 的 `equals/hashCode` 去重。事件和所有嵌套容器必须不可变，否则哈希语义可能
在执行期间变化。

### 6.5 `zip_concat`

事件 Map 没有默认字符串字段，因此必须拒绝：

```text
zip_concat does not support structured event elements
```

不能调用 `String.valueOf(event)`，否则会把 Map 的调试字符串格式固化成公开特征值。

### 6.6 `calc_delta_seq`

事件 Map 不是数值，必须拒绝。不能隐式读取 `value` 属性。

如果以后需要处理事件字段，应先增加显式字段投影算子。但首期标准注册表严格限制为 8 个算子，新的
投影算子应作为扩展能力或下一期标准能力处理。

## 7. 对 `supportsSequenceView` 的影响

事件 Map 泛化不需要推翻当前 `supportsSequenceView` 设计，两者是正交能力：

```text
supportsSequenceView：Kernel 能否直接消费 OperatorSequence
事件 Map 模型：OperatorSequence 的某些元素如何表示
```

建议保持：

| 算子 | supportsSequenceView | 说明 |
| --- | --- | --- |
| `discrete` | false | 不消费序列视图 |
| `log_base` | false | 不消费序列视图 |
| `slice_by_indices` | true | 按逻辑下标读取 |
| `find_indices` | true | 按逻辑下标遍历 |
| `get_seq_length` | true | 读取逻辑长度 |
| `count_distinct` | true | 遍历逻辑元素 |
| `zip_concat` | true | 可直接读取视图，但拒绝 Map 元素 |
| `calc_delta_seq` | false | 当前使用物化 List，且要求数值元素 |

`zip_concat` 支持读取 `SequenceView`，不等于支持任意元素类型。因此其能力声明为 true 与拒绝事件 Map
并不矛盾。

## 8. 推荐修改顺序

### 第一阶段：修复正确性

1. 输入边界取消固定字段改名和 `timestamp` 特判；
2. `SequenceBlock` 对 Map/List 做递归防御性复制；
3. 输出边界递归物化事件 Map；
4. 统一索引字段值和查询 key 的归一化；
5. 为以上行为补充测试。

### 第二阶段：清理协议边界

1. 从 `OperatorSequence` 删除 `filterByColumn()`；
2. 增加独立的 `SequenceFieldAccessor`；
3. `SequenceView` 通过访问器过滤；
4. 索引 Provider 通过访问器读取字段；
5. 从标准核心注册表移除 `industryId` 硬编码。

### 第三阶段：增强构建期校验

1. `zip_concat.infer()` 拒绝 `EVENT_SEQUENCE`；
2. `calc_delta_seq.infer()` 拒绝 `EVENT_SEQUENCE`；
3. 运行时继续保留元素校验；
4. 如果未来引入可选 Schema，再补充字段投影的静态类型推断。

## 9. 建议补充的测试

至少应增加以下测试：

1. 输入任意字段名，输出字段名完全不变；
2. `item_id` 不会自动改成 `itemId`；
3. `timestamp` 的 Integer、Double、String 等值不会被输入层改写；
4. 同时存在 `item_id` 和 `itemId` 时两个字段都保留；
5. 输入后修改原始事件 Map，不影响 `SequenceBlock`；
6. 输入后修改嵌套 List，不影响 `SequenceBlock`；
7. 输入后修改嵌套 Map，不影响 `SequenceBlock`；
8. 输出 Map、嵌套 Map 和嵌套 List 均不可修改；
9. 数字索引字段和数字查询 key 能正确匹配；
10. 字符串 `"1"` 和整数 `1` 是否等价由 Provider 规则明确决定；
11. 字段缺失与字段值为 null 的索引行为分别覆盖；
12. 两个 selection 不同的 `SequenceView` 不共享错误索引；
13. 空事件序列上的非法 `zip_concat`/`calc_delta_seq` 在初始化阶段即失败；
14. `count_distinct` 对嵌套 Map/List 事件保持稳定；
15. Single 与 Native Batch 的异常类型和行号保持一致。

## 10. 本次验证结果

已完成以下验证：

- `mvn clean package`：通过；
- `DagEngineSelfTest`：通过；
- 外部黄金用例：5 个单请求和 1 个离线批请求全部通过；
- `git diff --check`：未发现空白错误，仅存在工作区 LF/CRLF 提示。

现有测试没有覆盖深度不可变性，并且把字段别名归一化和 `timestamp` 转换视为正确行为，所以测试通过
不能证明已经满足“任意属性原样进出”的新契约。

## 11. 最终建议

保留“事件使用不可变 `Map<String, Object>`”的总体方案，但按以下原则收口：

- 通用输入边界不解释业务字段；
- Map/List 必须深度不可变；
- `OperatorSequence` 只保留序列读取能力；
- 字段读取、别名、类型和归一化通过 Provider/Accessor 注册；
- 索引构建和查询必须使用相同归一化规则；
- `zip_concat` 与 `calc_delta_seq` 明确拒绝结构化事件，不做隐式字段投影；
- `supportsSequenceView` 继续作为 Kernel 输入能力契约保留。

完成这些调整后，该方案可以在保持实现简单的同时真正移除固定 5 字段业务模型，并为未来可选 Schema、
列式存储和字段投影扩展保留空间。
