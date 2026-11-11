# 单请求列式在线输入

新增 `FeatureDagEngine.generateColumnar(OnlineColumnarGenerateRequest)`，接收
`特征名称 → 按候选顺序排列的值列表`。原来的 `generate(OnlineGenerateRequest)`、
`OnlineGenerateRequest.borrowed()` 和所有 `generateBatch()` 用法保持不变。
这是独立入口，没有增加 `generate()` 重载，也没有修改密封接口 `GenerateRequest`。

## 调用示例

以下示例假设模型配置中 `price` 是 ITEM/INT/SCALAR，`category` 是
ITEM/STRING/SCALAR，且当前输出依赖这两个源特征。

```java
import com.example.featuredag.api.GenerateResult;
import com.example.featuredag.api.OnlineColumnarGenerateRequest;
import java.util.List;
import java.util.Map;

List<String> candidateIds = List.of("item_001", "item_002");
Map<String, List<?>> columns = Map.of(
        "price", List.of(99, 50),
        "category", List.of("book", "music"));

OnlineColumnarGenerateRequest request = OnlineColumnarGenerateRequest.borrowed(
        executionId,
        sharedValues, // 与旧入口相同：共享标量用单元素 List，共享序列用完整 List
        candidateIds,
        columns);

GenerateResult result = dagEngine.generateColumnar(request);
// result.candidateFeatureValues().get(i) 对应 candidateIds.get(i)。
// 共享输出仍从 result.featureValues() 读取。
```

需要请求快照时使用普通构造器：

```java
OnlineColumnarGenerateRequest request = new OnlineColumnarGenerateRequest(
        executionId, sharedValues, candidateIds, columns);
```

普通构造器复制、冻结 Map/List 容器，包括嵌套序列和事件字段。
其余值按标量保存，不能依赖它复制自定义可变业务对象。
`borrowed()` 不复制输入特征 Map、列和嵌套值，候选 ID 列表仍保存快照。
从构造请求到最后一次 `generateColumnar()` 返回，调用方必须保持所有借用输入不变，
包括其他线程和访问器取得的引用；返回后可复用输入容器。输出沿用现有的不可变编码契约。

## 输入约定

| 内容 | 示例 | 语义 |
| --- | --- | --- |
| 候选 ID | `["item_001", "item_002"]` | 固定顺序并确定候选数量；不依赖某一列推断数量 |
| ITEM 标量列 | `price: [99, 50]` | 每个单元直接是标量，无需包装成 `[99]` |
| ITEM 序列列 | `tags: [["sale", "hot"], ["new"]]` | 外层是候选维度，内层是单个候选的序列 |
| ITEM 事件序列列 | `events: [[{"id":1}], []]` | 单元是事件 Map 列表，沿用 EVENT_SEQUENCE 解码和校验 |
| 缺失单元 | `price: [99, null]` | 对应旧行式输入中第二个候选没有 `price` key |
| 空序列单元 | `tags: [[], ["new"]]` | 第一个候选显式传入空序列，按原有算子和输出规则处理 |
| 整列缺失 | Map 中没有 `price` | 所有候选都缺失该源特征 |

- 所有已提供的列必须非 null，长度必须等于候选数量；即使列不被 DAG 使用也校验长度。
- 每列顺序必须与候选 ID 一致。重排、筛选候选时须同步处理每一列。
- 候选 ID 必须非空白，允许重复；位置是唯一对齐依据，不按 ID 去重或查找。
- 缺失单元/整列缺失使用 BASE 源的默认值；没有源默认值时沿用源绑定失败语义，报出特征名和候选下标。
  DERIVED 的 `dft` 不掩盖输入解码或源绑定错误。
- `null` 用于表示单元缺失；序列内部的 null 元素保持原有解码/算子语义。
- 零候选允许所有列为空或无列；有候选而无 ITEM 输入列仍保留结果位置。
- Map key 使用引擎的源输入名。业务字段名映射、分隔符拆分、字符串转数值仍由业务适配层处理。
  例如字符串 `"sale^hot"` 不能直接当 SEQUENCE 单元，需先转换成 `List`。
- BIGINT 沿用精确整数转换及范围校验。只解码目标 DAG 依赖的 ITEM 列。
- 推荐 `ArrayList` 或其他支持随机访问的列；非随机访问列表在解码时转换一次，避免平方复杂度。

## 执行与兼容性

列式入口按列完成必要解码，`ExecutionContext` 保存已解码列，源节点直接生成候选向量。
普通执行路径不创建逐候选的输入 Map，不创建标量单元素 List，也不从逐候选 Map 再收集整列。
已有扩展执行器若读取 `ExecutionContext.candidates()`，会按需得到只读行视图；只有显式
遍历某一行的条目时才物化该行，不影响标准源绑定路径。

表达式、逻辑 DAG、物理计划和算子接口不变；行式与列式入口共用输出编码。
结果仍为 `GenerateResult`，因此原有结果合并逻辑可以复用。
新入口只支持 ONLINE 单请求；不扩展离线及多请求分组批入口。

这不是完全零复制：候选 ID 快照、必要类型转换、序列只读包装、事件序列转换、
候选向量的既有防御复制和输出构建仍存在。性能收益取决于 ITEM 特征数、候选数和算子成本，
不能用本机验证代替生产压测。

## 验证

`OnlineColumnarInputTest` 覆盖与旧入口结果一致、标量/序列/事件序列、BIGINT、源默认值、
衍生默认值、缺失与空序列区别、零候选、快照隔离、借用输入、输出隔离、列长度校验、
候选顺序、非随机访问列和并发调用。

```bash
mvn -Dtest=OnlineColumnarInputTest test
./scripts/run-self-test.sh
```

## 列式结果入口

下游也消费特征列时，调用 `generateColumnarResult(request)`。它直接编码候选向量，不经过 `GenerateResult` 的逐候选 Map；原 `generateColumnar` 和旧行式接口保持不变。

```java
OnlineColumnarGenerateResult result = engine.generateColumnarResult(request);
List<String> ids = result.candidateIds();
Map<String, List<?>> columns = result.candidateFeatureColumns();
// columns.get("discount_price").get(i) 对应 ids.get(i)。
```

候选输出键使用配置的 `store_name`；标量列如 `[79.2, 40.0]`，序列列如 `[[1, 2], [3]]`。序列仍应用 `seq_max_length` 截断/补齐。共享输出 `sharedFeatureValues()` 沿用旧契约：标量为单元素 List，序列为元素 List。结果容器及物化的嵌套集合只读，候选 ID 会生成快照。

复现三种入口的性能对比及 GC/JIT/CPU 采集：

```bash
bash scripts/run-columnar-profile.sh
```

使用 Java 21，普通基准各三轮、诊断各一轮；每轮预热 20 秒、测量 30 秒。JFR 在诊断轮次预热前启用，分析时用请求事件筛选测量窗口；GC 日志覆盖整个 JVM 生命周期。不要将诊断轮次与普通轮次混合统计。

诊断导出与分析：`bash scripts/export-columnar-jfr.sh`。原始 JFR 包含分配采样、编译阶段及 CPU 事件；JSON 导出选择请求、GC、编译和 CPU 样本以控制大小。
