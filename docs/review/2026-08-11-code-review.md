# 代码 Review 报告：feature-dag-engine

> 历史说明：本文记录的是首期算子收口之前的仓库快照，其中的 Demo、基准和非首期算子已删除；当前范围以根目录 `README.md`、`AGENTS.md` 和首期算子 UT 矩阵为准。

| 项 | 内容 |
|---|---|
| 审查日期 | 2026-08-11 |
| 审查范围 | `src/main/java`、`src/test/java`、`src/jmh`、`docs/`、`scripts/`、`pom.xml`、README（全量约 1.1 万行 Java） |
| 基线 | 当前分支 `agent/runtime-observability`，HEAD `3ae79af`（Add production observability controls） |
| 方式 | 逐层通读（L0 定义/配置/表达式 → L1 逻辑/规划 → L2 物理/算子 → 运行时 → 观测 → demo/测试）+ 实际编译与运行验证 |
| 结论 | 架构分层与约束（C1–C10）执行到位，测试覆盖扎实；存在 1 项公共 API 集成缺口（P1）、3 项中等问题（P2）与若干死代码/小问题（P3） |

---

## 1. 验证状态

以下命令在本机实际执行，全部通过：

```bash
# 环境（本机 JDK/Maven 不在 PATH，需手动导出）
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
export PATH="$JAVA_HOME/bin:/c/dev/apache-maven-3.9.16/bin:$PATH"

# 编译主代码与测试代码
mvn -q -DskipTests test-compile dependency:build-classpath \
  -Dmdep.outputFile=target/test-classpath.txt

# 启用断言运行端到端自测
CP="target/test-classes;target/classes;$(cat target/test-classpath.txt)"
java -ea -cp "$CP" com.example.featuredag.DagEngineSelfTest
# → All DAG engine self tests passed.

# 三个 Demo 入口
java -cp "target/classes;$(cat target/classpath.txt)" com.example.featuredag.demo.DagDemo
java -cp "target/classes;$(cat target/classpath.txt)" com.example.featuredag.demo.OfflineBatchDemo
java -cp "target/classes;$(cat target/classpath.txt)" com.example.featuredag.demo.OnlineGroupedBatchDemo
```

- `DagDemo`：离线/在线三天计数结果一致 `auid_omnichannel_paid_cnt_3d=[1]`，派生特征 `=[0]`，符合 Demo 输入契约。
- `OfflineBatchDemo` / `OnlineGroupedBatchDemo`：行数、分组数、空 group、广播与逐候选输出均符合预期。

---

## 2. 发现汇总

| 级别 | 数量 | 主题 |
|---|---|---|
| P1 | 1 | 公共 API 无法触达 `extractIndustry` 与序列索引融合（sequence-key-count）路径 |
| P2 | 3 | 配置层静默忽略未知 JSON 字段；解析器链式调用静默拼接参数；README 与算子注册表漂移 |
| P3 | 2 类 | 死代码/未完成字段清单；类型与运行时不一致小点、性能小点 |

---

## 3. 发现明细

### P1 — 公共 API 无法触达 `extractIndustry` 与序列索引融合路径

**现象**

公共 `generate` API 提供的 RAW 序列源（普通 Java `List`）在运行时只会变成 `ListSequenceValue`，其 `raw()` 返回普通 `List`，**永远不会是 `SequenceValue`**：

- 解码层对 SEQUENCE 形状源仅做 `FeatureValueCollections.immutableList(values)`：`api/FeatureInputDecoder.java:79-81`；
- 源节点取值后由 `wrapSource` 包装为 `ListSequenceValue`：`runtime/DagRuntime.java:405-414`；
- `extractIndustry` 通用求值器 `asSequence(args.get(0))` 要求 `instanceof SequenceValue`：`operator/OperatorRegistry.java:98-106`、`497-500`；
- 融合执行器 `SequenceKeyCountExecutor` 两条入口同样要求 `sequenceRaw instanceof SequenceValue`，否则抛 "First input must be SequenceValue"：`runtime/SequenceKeyCountExecutor.java:55-57`、`115-120`。

全仓库不存在任何「List → SequenceBlock」的转换点（`new SequenceBlock` 只出现在测试代码中，`DagEngineSelfTest.java:2963/3388/3529`）。因此：

1. 通过公共 API 传入普通 List 序列，`extractIndustry`（通用路径）与 `count-after-keyed-sequence-filter` 融合路径**必然在运行期抛错**；
2. 目前融合路径的测试全部靠绕过公共 API、直接向 `ExecutionContext` 注入手工构造的 `SequenceBlock`（如 `DagEngineSelfTest.java:181-190`、`2899` 起）。

**影响**

- README 将 `extractIndustry` 列为"已支持 Runtime 计算"，`docs/architecture/operator-optimization-extension.md` 描述的融合闭环在公共输入契约下不可达；
- `demo/ExampleFeatures.same_industry_count`（`count(extractIndustry(...))`）在 `OnlineGenerateRequest` 普通 List 输入下会运行期崩溃；
- 构建期无任何防护（C6 只校验类型/形状/域，不校验"序列表示的来源"），问题延迟到运行期才暴露。

**建议**

- 在解码层为 `EVENT_SEQUENCE` 类型源提供 List → `SequenceBlock` 转换（外部事件对象 → `SequenceEvent`），或
- 在两处入口（`asSequence`、`SequenceKeyCountExecutor`）接受 List 并转换为 `SequenceBlock`；
- 在此之前，README 与 AGENTS.md 应把该路径标注为"需注入式输入（SequenceValue），公共 API 暂不可用"。

---

### P2-1 — 配置层静默忽略未知 JSON 字段

`FeatureConfig` / `FeatureSetConfig` 通过 `@JsonAnySetter` 收集 `additionalProperties` 但不校验（`config/FeatureConfig.java:60,83-86`），且未开启 `FAIL_ON_UNKNOWN_PROPERTIES`。

后果：字段拼写错误被静默忽略并**改变语义**。典型场景：`entity_scop: ["ITEM"]` 拼错后，BASE 特征实体域回落默认 `USER`（`config/FeatureConfigMapper.java:76-79`），在线计划中该源从 candidates 移到 shared 域，行为悄悄变化且无任何报错。

建议：`additionalProperties` 非空即抛 `IllegalArgumentException`（或至少对 `entity_scopes` / `to_use` / `value_shape` 等关键字段做拼写容错检查）。

### P2-2 — 表达式解析器把链式调用静默拼接为同一调用的参数

`expression/ExpressionParser.java:187-213`：`parseIdentifierOrCall` 对 `f(a)(b)` 不校验嵌套语义，而是把两批参数**拼进同一个 `AstCall`**。

- `slice_v3_typed({"start": 4})(seq)` 是刻意支持的柯里化语法（有测试覆盖，`DagEngineSelfTest.java:226-231`）；
- 但同样的宽松规则作用于所有算子：`coalesce(a)(b)` 会被静默重解释为 `coalesce(a, b)`，错误表达式得不到报错。

建议：仅对注册表声明支持柯里化的算子放行链式调用，其余一律报语法错误。

### P2-3 — README 与算子注册表漂移（least/div 合并后未同步）

- `OperatorRegistry.standard()` 实际注册 **40** 个算子，README 写 **38**；
- `least` 与 `div` 已可执行（`ed03dc9` "Add least and div operators" 合并），但 README"已支持 Runtime 计算"表缺失这两项。

附表（README 现有表格共 22 项可执行 + 16 项未实现 = 38；实际可执行 24 + 未实现 16 = 40）：

| 项 | README | 实际 |
|---|---|---|
| 注册总数 | 38 | 40 |
| 可执行 | 22（缺 `least`、`div`） | 24 |
| 已注册未实现 | 16 | 16 |

建议：按 AGENTS.md「提交时同步文档」约定补全算子表。

---

### P3 — 死代码与未完成字段（多为扩展占位，建议清理或注明）

| 位置 | 说明 |
|---|---|
| `logical/ParameterNode.java` + `NodeType.PARAMETER` | 从未被实例化（全仓库无引用） |
| `config/MappedFeatureSet.unresolvedOnlineScopes` | `FeatureConfigMapper` 恒传 `Set.of()`（`FeatureConfigMapper.java:139`） |
| `config/FeatureConfig.isFeedback` | 有字段与 getter，无任何消费方 |
| `definition/FeatureRole.MODEL_INPUT/INTERMEDIATE`、`logical/OutputRole` | `LogicalDagBuilder` 计算 `outputRole` 后下游无人读取 |
| `logical/OperatorNode.operatorParams` | 构建时恒为 `Map.of()`（`LogicalDagBuilder.java:288`） |
| `operator/OperatorDefinition.supportsSequenceView` | 注册表传值，无任何代码消费 |
| `runtime/RuntimeNodeState.cacheHit/cacheSource/allocatedBytes/fallbackUsed` | setter 从未被调用，快照读到的恒为默认值 |
| `runtime/SequenceView.slice` | main 代码无调用（仅测试使用） |
| `physical/CachePolicy.ROW/BATCH/USER_GROUP/PARTITION`、`ExecutionMode.ROW/USER_GROUP`、`ExecutionStage.CANDIDATE_SET`、`runtime/ExecutionStatus.SKIPPED` | 枚举成员从未被产出 |

### P3 — 类型/运行时一致性小点

1. **`least` 推断与返回值类型可能不一致**（`OperatorRegistry.java:230-251`）：混入 DOUBLE 输入时推断 `DOUBLE`，但最小值若恰为 Integer 参数则返回 `Integer`；`coalesce` 同理（推断 input0 类型但可返回其他类型）。当前输出序列化无害，但与 C6 声明/推断一致性精神不符。
2. **`count` 推断过严**：构建期要求输入 shape 为 `SEQUENCE`（`OperatorRegistry.java:113-118`），运行期却支持 `Collection`/数组——`count([1,2,3])`（数组字面量 shape=OBJECT）被构建期拒绝，两边口径不一致。
3. **`DagRuntime.executeNode` 捕获 `Throwable`**（`DagRuntime.java:86-91`）：`OutOfMemoryError` 等 `Error` 被包装成 `IllegalStateException` 后重抛，改变异常类型；观测层 `measure` 同样捕获 `Error`（`api/FeatureDagEngine.java:449`）。建议只处理 `RuntimeException`。
4. **`FeatureInputDecoder` 标量取 `values.getFirst()`**（`FeatureInputDecoder.java:81`）：空 List 抛裸 `IndexOutOfBoundsException`，无特征名定位信息。
5. **`ValueShape.CANDIDATE_VECTOR` 源不完整**：声明 `VECTOR` 的 RAW 源在解码时被当作标量取首元素（解码只区分 SEQUENCE），该路径无测试覆盖，语义未闭环。
6. **BASE 特征 scope override 为空集时回落默认域**（`FeatureConfigMapper.java:76-79`）：`rawFeatureScopes` 显式空集无法表达"强制空域"，会被静默替换为默认 `USER`。

### P3 — 性能小点

1. **候选缓存 key 的哈希开销**：`OperatorInvocationCacheKey` 每次候选循环都构建新 key 并入 `LinkedHashSet`（`DagRuntime.java:236-250`）。`ArrayList.hashCode` 不缓存，共享同一 List 实例的候选会对整个序列反复计算哈希（O(序列长) × 候选数）；`equals` 有 `o == this` 短路但 hashCode 没有。建议序列参数改用对象身份（`System.identityHashCode`）或延迟哈希。
2. **`BitmapSelection` 名不副实**：构造时立即把 `BitSet` 转成 `int[]`（`BitmapSelection.java:8-16`），"位图"紧凑优势在构建后即丢失；语义正确，仅与注释宣称的表示不符。

---

## 4. 架构亮点（评审认可项）

- **C1–C10 约束执行到位**：规划元数据外置（C8）、物理单槽输出（C9）、运行时只按 `executorId` 路由（C10）名副其实；`PhysicalRewriteRegistry` 的优先级排序与消费冲突消解逻辑正确。
- **fail-fast 与不可变纪律**：`FeatureDefinition` / 逻辑节点 / 物理计划全部构造期校验 + 不可变拷贝；注册表重复注册、缺失 executor/provider 均抛错不静默。
- **批执行隔离**：`groupOffsets` 映射、逐组缓存 key（含 `groupIndex`）、`SequenceKeyCountExecutor` 按组构建索引/去重/顺序还原；测试覆盖空 group、零 candidate、重排候选等边界。
- **观测闭环**：确定性采样（FNV hash of planId+executionId）、慢请求/失败强制采集、`AsyncRuntimeObserver` 非阻塞 offer + drop 计数 + 隔离 sink 异常，与 `runtime-observability.md` 语义一致。
- **测试覆盖**：3537 行自测覆盖解析、构图、环检测、融合、缓存去重、SequenceView 选择、在线/离线一致性、观测采样等；断言消息清晰；`FeatureValueCodecSelfTest` 被主入口调用。

---

## 5. 建议优先级

1. **P1**：补 List → `SequenceBlock` 转换（解码层或两处入口），或先在 README/AGENTS 标注该路径当前仅支持注入式输入；
2. **P2-1**：配置层开启未知字段校验（改动小、收益大）；
3. **P2-3**：同步 README 算子表（`least`/`div`，38→40）；
4. **P3**：清理死代码（一次提交），解析器链式调用与 `Throwable` 捕获按需收紧。

---

## 6. 复现命令

见第 1 节。注意 `scripts/run-self-test.sh` 在 Windows Git Bash 下因 classpath 分隔符（`:` vs `;`）不兼容，需按第 1 节方式直接运行；Linux/WSL 下脚本应正常。

## 附录 A：算子注册表与 README 对照（2026-08-11 基线）

注册总数 40：

- 基础 7：`coalesce`、`normalize`、`extractIndustry`、`count`、`add`、`log`、`multiply`
- 序列 10：`find_list_index_typed`、`list_index_typed`、`greater_in_sequence_typed`、`greater_than_index_typed`*、`reverse_typed`*、`slice_v3_typed`*、`intersection_typed`*、`uniq_key_index`*、`list_2_map`*、`thf_default_`*
- 转换 6：`64`*、`value2key`*、`k2v`*、`k2v_f`*、`v2v`*、`multi_v2`*
- 标量 9：`sub`、`sign`、`list_multi`*、`div_num`、`round`、`div`、`least`、`dis2xl`*、`default_key_if`*
- opsList 8：`discrete`、`log_base`、`slice_by_indices`、`find_indices`、`get_seq_length`、`count_distinct`、`zip_concat`、`calc_delta_seq`

（`*` 为已注册但运行期抛 `UnsupportedOperationException` 的 16 个算子）
