# SequenceView 算子支持落地方案

## 0. 实施状态

本方案已在通用算子链路落地：`PhysicalPlanner` 将能力固化为
`SequenceViewInputMode.DIRECT/MATERIALIZE`，`DagRuntime` 在 Single 与 Batch Kernel 共同入口执行
适配，内置算子通过 `OperatorSequence` 统一读取逻辑视图。当前直接支持视图的首期算子为
`slice_by_indices`、`find_indices`、`get_seq_length`、`count_distinct`、`zip_concat`；
`calc_delta_seq` 保持物化模式，并拒绝事件元素（不做隐式数值投影，见第 2.4 节）。

事件模型已泛化（2026-08-13）：固定 5 字段的 `SequenceEvent` record 已删除，事件统一为不可变
`Map<String, Object>`；`SequenceBlock` 改为行式存储；`OperatorSequence.filterByColumn(column,
value)` 取代 `filterByIndustry`。输入输出采用纯透传契约：输入边界不改写、不转换任何业务字段，
Map/List 深度防御复制为不可变容器，输出按输入字段名、值与顺序递归物化（见第 2.4 节）。

## 1. 背景与目标

方案提出时，项目已经具备三项彼此相关但尚未闭环的能力：

- `operator.OperatorSequence` 是算子层可见的最小序列读取协议；
- `runtime.SequenceBlock` 与 `runtime.SequenceView` 是运行时行式序列（事件 = 不可变 Map）及
  零拷贝视图实现；
- `OperatorDefinition.supportsSequenceView()` 当时只是算子注册元数据，没有规划或运行时代码消费。

现状还存在声明与实现不一致：部分声明支持视图的算子仍只接受 `List`，而
`get_seq_length`、`count_distinct` 已能通过 `OperatorSequence` 读取视图却声明为不支持。

本方案的目标是让 `supportsSequenceView` 成为可验证、可规划、可执行的能力契约：

1. 支持视图的 Kernel 直接消费 `OperatorSequence`，保持零拷贝；
2. 不支持视图的 Kernel 在调用边界收到按视图逻辑范围物化的只读 `List`；
3. Single、Native Batch 与 `SingleLoopBatchOperatorKernel` 三条路径逐行等价；
4. 物理计划固化输入适配策略，运行时不按业务算子名决策（C10）；
5. 缓存 key 覆盖具体视图，不能让同一 `SequenceBlock` 的不同 selection 发生碰撞。

非目标：

- 本方案不定义算子是否“产生” `SequenceView`；输出保留视图与输入消费能力是两个概念；
- 不把所有普通 Java `List` 转换成 `SequenceBlock`；
- 不为算子定义任何事件字段读取语义：算子不解析事件字段，事件属性只由输入输出边界透传
  （等值/去重按元素对象完整内容，见第 2.4 节）；
- 不为任何业务算子在 `planning`、`physical` 或 `runtime` 中增加名称分支。

## 2. 术语与契约

### 2.1 OperatorSequence

`OperatorSequence` 位于 `operator` 层，暴露 `size()`、`elementAt(int)`、
`filterByColumn(String, Object)` 等最小能力，不固化任何业务字段。
首期算子只能依赖该协议或 JDK 集合协议，不得引用 `runtime.SequenceView`、
`runtime.SequenceBlock` 和 `runtime.SequenceValue`，以保持 L0 到 runtime 的单向依赖（C1）。

### 2.2 SequenceView

`SequenceView` 是 `SequenceValue` 的运行时实现，以 `SequenceBlock + SequenceSelection`
表示一个逻辑序列。`elementAt(i)` 必须按照逻辑下标读取 selection 指向的底层元素；过滤或
切片产生的新视图不得回退为遍历完整 `baseBlock`。

### 2.3 supportsSequenceView

`supportsSequenceView()` 的第一版精确定义如下：

> 当返回 `true` 时，该算子的 Single Kernel 以及它注册的 Native Batch Kernel（如有）能够在
> 任意序列参数位置直接接收 `OperatorSequence`，并严格按照其逻辑长度、顺序和元素范围求值。
> 当返回 `false` 时，通用执行路径必须在 Kernel 调用前把 `OperatorSequence` 物化为只读 `List`。

该定义包含以下约束：

- `true` 是正确性承诺，不只是性能提示；
- Native Batch 与 Single 必须作出相同声明，不能一个支持、另一个不支持；
- `false` 不表示拒绝视图，而表示通过统一物化适配后执行；
- 物化只针对实际为 `OperatorSequence` 的参数，普通 `List`、标量和配置参数保持原样；
- 物化结果的元素等于依次调用 `elementAt(0..size-1)` 的结果，不能物化完整底层块；
- `supportsSequenceView` 只描述输入消费能力，不描述返回值是否是视图。

第一版保留布尔值，是因为当前 8 个标准算子的序列参数可以统一采用同一策略。若未来出现
同一算子只有部分序列参数、或只有某个 Kernel 能直接消费视图，须升级为按参数、按 Kernel
声明的能力模型，见第 11 节。

### 2.4 事件模型与纯透传契约

事件元素不再使用固定字段的 `SequenceEvent` record，而是不可变 `Map<String, Object>`
（属性全集）。`SequenceBlock` 按行存储这些 Map，构造时对 Map/List 做**递归防御复制**为
不可变容器，其余类型按标量透传（调用方不得传入可变业务对象）；
`SequenceValue.elementAt(i)` 返回对应行 Map；`filterByColumn` 经
`SequenceBlock.columnValueAt` 按名取列，核心协议不固化任何业务字段。

输入边界（`FeatureInputDecoder`）只验证：

- 每个事件必须是 Map；
- 每个 key 必须是 String；
- 深度防御复制与不可变化由 `SequenceBlock` 统一完成。

输入边界**不识别、不改写、不转换任何业务字段**：不做 `item_id→itemId` 之类别名归一化，不为
名为 `timestamp` 的字段强制转换类型，字段名、字段值、迭代顺序与输入保持一致。字段提取、别名、
类型校验和查询 key 归一化属于字段访问器/索引 Provider 职责，不属于通用事件输入边界。

输出边界（`ExternalValueMaterializer`）直接透传事件行 Map（已深度不可变），公共 API 输出与
输入结构对称：字段名、值与顺序不变。

由此产生的算子语义：

- 等值口径：`find_indices` 目标匹配与 `count_distinct` 去重按 Map 内容相等（全属性相等，
  含嵌套容器；深度不可变保证哈希在执行期稳定）；
- `calc_delta_seq` 与 `zip_concat` 在 **infer 构图期**拒绝 `EVENT_SEQUENCE` 输入（空序列也
  失败），运行时元素检查保留为防御——不做隐式数值投影、不把事件结构 dump 固化为特征值；
- 序列索引的索引 key 与查询 key 使用**同一归一化器**（见
  `docs/architecture/operator-optimization-extension.md`），字段缺失与 null 的语义由 Provider
  的归一化规则统一决定。

## 3. 分层设计

```text
OperatorDefinition.supportsSequenceView
                |
                v
PhysicalPlanner 推导 SequenceViewInputMode
                |
                v
PhysicalNode.executorConfig 固化 DIRECT / MATERIALIZE
                |
                v
DagRuntime 按计划适配 Single 或 Batch 参数
                |
                +--> DIRECT: 原样传递 OperatorSequence
                |
                +--> MATERIALIZE: 按逻辑范围生成只读 List
                |
                v
SingleOperatorKernel / BatchOperatorKernel
```

职责划分：

- `operator`：声明能力，并提供不依赖 runtime 的序列读取协议；
- `physical`：把注册能力转换为明确的物理输入策略；
- `runtime`：只执行已固化策略，负责视图到 Kernel 参数的边界适配；
- `operator.builtin`：保证声明为 `true` 的 Single/Native Batch 实现确实使用通用序列协议；
- 专用 `PhysicalExecutor`：继续按自身注册契约处理 `SequenceValue`，不经过通用算子的该适配链。

`MaterializationPolicy.VIEW/LAZY/MATERIALIZE` 描述节点输出结果的物化策略，不能复用来表达
Kernel 输入能力。输入适配应使用独立的 `SequenceViewInputMode`，避免输入与输出语义混淆。

## 4. API 与物理计划变更

### 4.1 新增输入模式枚举

建议在 `physical` 层新增：

```java
public enum SequenceViewInputMode {
    DIRECT,
    MATERIALIZE
}
```

语义：

- `DIRECT`：Kernel 原样接收 `OperatorSequence`；
- `MATERIALIZE`：运行时在 Kernel 边界把 `OperatorSequence` 转为只读 `List`。

### 4.2 PhysicalPlanner 固化策略

对每个未融合的 `OperatorNode`，`PhysicalPlanner` 从注册表读取定义并写入
`executorConfig.sequenceViewInputMode`：

```java
OperatorDefinition definition =
        operatorRegistry.require(operator.operatorName());
config.put(
        "sequenceViewInputMode",
        definition.supportsSequenceView()
                ? SequenceViewInputMode.DIRECT
                : SequenceViewInputMode.MATERIALIZE);
```

物理计划必须显式携带该字段。为了让旧的手工计划或序列化计划安全升级，运行时缺失该字段时
建议默认 `MATERIALIZE`，以正确性优先；待所有计划生产方完成迁移后，可改成缺失即 fail-fast。

专用 Rewrite 不需要自动继承该字段。`ExecutorType.SPECIALIZED` 的输入类型和适配方式属于对应
`PhysicalExecutor` 契约，由其 `validate` 校验。

### 4.3 注册阶段一致性

`OperatorRegistry.register` 继续同时注册 Single Kernel、Native Batch 或 Scalar Adapter。
第一版不新增反射式能力检查，因为无法仅靠 Java 类型证明 Kernel 是否正确读取逻辑视图。
一致性由实现规范和强制测试保证。

## 5. 运行时输入适配

### 5.1 通用适配器

在 runtime 层新增包内工具，例如 `SequenceViewArgumentAdapter`：

```java
final class SequenceViewArgumentAdapter {
    private SequenceViewArgumentAdapter() {}

    static Object adapt(Object value, SequenceViewInputMode mode) {
        if (mode == SequenceViewInputMode.DIRECT
                || !(value instanceof OperatorSequence)) {
            return value;
        }
        OperatorSequence sequence = (OperatorSequence) value;
        List<Object> result = new ArrayList<Object>(sequence.size());
        for (int index = 0; index < sequence.size(); index++) {
            result.add(sequence.elementAt(index));
        }
        return Collections.unmodifiableList(result);
    }
}
```

这里必须判断 `OperatorSequence`，不能在通用执行器中判断具体 `SequenceView` 类型。这样既保持
C1/C10，又保证完整 `SequenceBlock` 与局部 `SequenceView` 使用相同算子输入协议。

不能复用 `ExternalValueMaterializer`：它面向公共 API 的编码语义（不可变集合包装、递归物化），
与 Kernel 边界适配不同。事件元素本身已是不可变 Map，Kernel 边界适配仍保留原始元素对象，
不复制元素。

### 5.2 Single 路径

`DagRuntime.applySingleOrBatchByInputDomain` 的 `EvaluationDomain.NONE` 分支在调用
`OperatorRegistry.evaluate` 前适配每个原始参数：

```text
ValueHandle.raw()
    -> SequenceViewArgumentAdapter.adapt(value, plannedMode)
    -> OperatorRegistry.evaluate(...)
```

模式只能从 `PhysicalNode.executorConfig` 读取，不能在运行时再次查询算子名或重做规划决策。

### 5.3 Batch 路径

Batch 输入通过只读 `BatchColumn` 延迟取值。新增包装列：

```java
final class SequenceAdaptingBatchColumn implements BatchColumn {
    private final BatchColumn delegate;
    private final SequenceViewInputMode mode;
    private final IdentityHashMap<Object, Object> materialized;

    @Override
    public Object valueAt(int rowIndex) {
        Object value = delegate.valueAt(rowIndex);
        if (mode == SequenceViewInputMode.DIRECT
                || !(value instanceof OperatorSequence)) {
            return value;
        }
        // 同一 Batch 内共享同一个视图对象时只物化一次。
        Object cached = materialized.get(value);
        if (cached != null) return cached;
        Object result = SequenceViewArgumentAdapter.adapt(value, mode);
        materialized.put(value, result);
        return result;
    }
}
```

使用对象身份而不是 `equals`，原因是当前视图正确性边界就是具体 `SequenceValue` 对象；不同
selection 即使共享同一 `SequenceBlock` 也不能复用物化结果。包装列只在一次 Batch 调用内存在，
不得进入注册表或跨请求复用。

该包装必须位于 Native Batch 与 Scalar Adapter 共同入口之前，从而保证：

```text
batch(adapt(arguments))[i] == single(adapt(arguments[i]))
```

### 5.4 异常语义

- 物化期间的 `size()`、`elementAt()` 异常必须保留原始 cause；
- Batch 物化异常必须关联当前 Batch 行，最终映射到 offline row 或 online group/candidate；
- 不能因 `MATERIALIZE` 静默读取完整 `baseBlock` 或改变元素顺序；
- 非法模式或物理计划字段类型错误必须 fail-fast。

## 6. 内置算子改造

在 `operator.builtin.OperatorSupport` 增加只依赖 `OperatorSequence`/JDK 的统一访问函数，避免每个
算子自行判断：

```java
static int sequenceSize(Object value, String operator, String argument);
static Object sequenceElementAt(
        Object value, int index, String operator, String argument);
```

两者至少支持 `OperatorSequence` 与 `List<?>`。如果某算子还声明支持 Java 数组或其他集合，必须
在其自身推断与求值契约中明确，不能由该工具无条件扩大标准算子输入面。

首期 8 个算子的目标口径如下：

| 算子 | 目标声明 | 实施要求 |
|---|---:|---|
| `discrete` | `false` | 标量计算；边界配置继续使用普通 `List`，无需视图直通 |
| `log_base` | `false` | 只消费标量，无需视图直通 |
| `slice_by_indices` | `true` | 第一个参数通过统一序列访问函数读取；下标列表仍按普通 `List` 校验 |
| `find_indices` | `true` | Single 扫描和 Native Batch 建索引都通过统一序列访问函数读取 |
| `get_seq_length` | `true` | 已通过 `OperatorSequence.size()` 支持，修正声明 |
| `count_distinct` | `true` | 已通过 `OperatorSequence` 遍历，修正声明并验证 Native Batch |
| `zip_concat` | `true` | 每个序列参数都通过统一序列访问函数读取；尾部配置 Map 保持原语义；事件 Map 元素拒绝拼接并明确报错 |
| `calc_delta_seq` | `false` | 元素必须是数值；事件 Map 元素保持拒绝并明确报错，不做隐式 value 投影 |

`calc_delta_seq` 若未来需要支持事件视图，应先显式定义输入投影协议，例如增加专门的数值序列抽象或
上游投影算子。不能在 `calc_delta_seq` 中隐式取事件 Map 的 `value` 字段，否则普通数值序列与事件
序列会产生隐藏的双重语义。同理，`zip_concat` 不隐式投影事件字段：事件无既定字符串契约，结构
dump 会随字段集合变化静默漂移，故直接拒绝。

声明为 `true` 的 Native Batch 实现还必须检查其批内复用 key：视图参数按对象身份区分，不能用
`baseBlock` 身份替代具体视图身份。

## 7. 缓存与复用正确性

### 7.1 先构造 key，后适配参数

批内复用（原生 Batch 的 identity 键 / 融合执行器缓存）必须基于原始运行时参数构造 key，再在真正调用 Kernel 时进行输入适配：

```text
原始参数（包含具体 SequenceView）
    -> cache/dedup key
    -> miss Batch
    -> DIRECT 或 MATERIALIZE 适配
    -> Kernel
```

如果先物化再构造 key，两个不同视图可能退化为内容相等的 `List`，既增加长序列哈希成本，也改变
当前基于具体视图边界的复用语义。

### 7.2 视图身份

当前 `SequenceView` 没有覆盖 `equals/hashCode`，具体对象身份能够区分不同 selection。缓存 key
至少要覆盖：

```text
physicalNodeId + groupIndex + concrete view + other arguments
```

序列索引和融合计数继续使用：

```text
keyDomain + concrete SequenceValue [+ normalizedKey]
```

禁止只使用 `SequenceBlock.handleKey()` 或 `baseBlock` 身份作为视图相关缓存 key。若未来希望让内容
等价的不同视图跨对象复用，必须先为 `SequenceSelection` 定义稳定内容指纹，并单独评估碰撞和版本
失效策略。

### 7.3 Batch 内物化复用

`MATERIALIZE` 模式可以在单次 Batch 调用内按对象身份缓存物化结果。缓存生命周期不得超过该调用，
且不同 online group 即使恰好持有相同业务数据，也不能因值相等而合并请求状态。

## 8. 测试方案

所有测试继续使用 Java `assert`，并由 `DagEngineSelfTest` 主入口显式调用。

### 8.1 元数据与计划测试

- 8 个标准算子的 `supportsSequenceView` 声明与第 6 节一致；
- `PhysicalPlanner` 为普通算子写入正确的 `SequenceViewInputMode`；
- 旧计划缺失字段时按约定默认 `MATERIALIZE`，或在完成兼容窗口后 fail-fast；
- 专用 Rewrite 不被通用输入模式错误覆盖。

### 8.2 Single 测试

构造同一个 `SequenceBlock` 上的以下视图：

- 连续 `RangeSelection`；
- 稀疏 `IndexSelection`；
- 密集非连续 `BitmapSelection`；
- 过滤后再切片的链式视图；
- 空视图。

对 `DIRECT` 探针算子断言收到的对象与原 `OperatorSequence` 为同一实例；对 `MATERIALIZE` 探针算子
断言收到只读 `List`，长度、顺序、元素等于视图逻辑范围且不包含未选中的底层事件。

### 8.3 Batch 测试

- `SCALAR_ADAPTER` 与 Single 逐行等价；
- `find_indices`、`count_distinct`、`zip_concat` 的 Native Batch 与 Single 逐行等价；
- 同一视图在一个 Batch 内重复出现时，`MATERIALIZE` 只执行一次；
- 不同视图共享同一底层块时分别物化且结果不串；
- zero-row、single-row、多行及多 online group；
- 视图读取失败时异常能定位到正确 row/group/candidate。

### 8.4 缓存测试

至少构造：

```text
base = [A, B, C, D]
view1 = [A, B]
view2 = [C, D]
```

验证：

- 两个视图不会命中同一个批内复用键、序列索引或计数缓存；
- 同一具体视图和相同其他参数可以复用；
- 缓存 key 使用原始视图，不使用物化后的 `List`；
- `DIRECT` 与 `MATERIALIZE` 的业务结果一致。

### 8.5 算子能力测试

每个声明 `true` 的算子至少直接以 `OperatorSequence` 调用 Single；具有 Native Batch 的还要直接
调用 Batch。该测试用于防止以后重新引入只接受 `List` 的实现而忘记修改元数据。

## 9. 实施步骤

建议按以下顺序小步落地：

1. 先新增失败测试，覆盖声明一致性、计划字段和 Single/Batch 输入适配；
2. 新增 `SequenceViewInputMode`，让 `PhysicalPlanner` 把能力固化进普通物理算子节点；
3. 新增 runtime 参数适配器及 Batch 包装列，同时保证缓存 key 仍基于原始参数；
4. 在 `OperatorSupport` 增加统一序列访问函数；
5. 依次改造 `slice_by_indices`、`find_indices`、`zip_concat`，修正
   `get_seq_length`、`count_distinct` 声明；
6. 将 `calc_delta_seq` 暂时修正为 `false`，另行设计事件到数值的显式投影；
7. 补齐视图 selection、Batch 等价、缓存隔离和异常定位测试；
8. 更新 `operator-single-batch-execution.md` 与 `operator-optimization-extension.md` 中的能力说明；
9. 显式运行 `./scripts/run-self-test.sh`；
10. 运行 `mvn clean package` 验证 Java 21 构建及打包。

实现期间不得覆盖工作区中与本方案无关的已有修改。

## 10. 验收标准

完成实现须同时满足：

- 全仓库至少有规划器和运行时两个生产消费点使用该能力，不再是死元数据；
- 物理计划可观察到 `DIRECT/MATERIALIZE` 决策；
- 运行时不出现业务算子名分支；
- 声明为 `true` 的算子不需要把 `OperatorSequence` 强制转换为 `List`；
- 声明为 `false` 的 Kernel 永远不会直接收到 `OperatorSequence`；
- Single、Native Batch、Scalar Adapter 对每一行结果和异常语义等价；
- 同底层块的不同视图在缓存、索引和 Batch 内复用中保持隔离；
- 首期算子及直接共用的 `operator.builtin` 支撑代码只使用 JDK 1.8 可用语法/API；
- `DagEngineSelfTest` 通过，`mvn clean package` 通过。

## 11. 后续演进

当布尔值无法准确表达能力时，建议兼容演进为参数级声明：

```java
enum SequenceInputMode {
    NOT_SEQUENCE,
    DIRECT_VIEW,
    MATERIALIZED_LIST
}

SequenceInputMode sequenceInputMode(int argumentIndex);
```

若 Single 与 Native Batch 能力不同，再把模式放入 Kernel capability：

```text
SingleKernelCapability.sequenceInputMode(argumentIndex)
BatchKernelCapability.sequenceInputMode(argumentIndex)
```

规划器据此分别固化 Single 与 Batch 输入策略。该演进适用于可变参数中混合序列与配置、部分参数只
接受普通 List、或 Native Batch 需要列式视图而 Single 只能物化的情况。在这些需求出现之前，
第一版布尔契约更简单，也足以闭环当前 8 个标准算子。
