## 目标
方案 A 泛化事件模型：删除固定 5 字段 SequenceEvent record，事件改为不可变 Map<String,Object>，现网任意属性经输入边界进入引擎并原样输出。

## 已拍板决策
1. filterByIndustry → 泛化 filterByColumn(column, value)，INDUSTRY 索引域保留为注册示例
2. calc_delta_seq 对事件序列保持拒绝 + 明确报错（不做隐式 value 投影）
3. zip_concat 拒绝事件 Map 元素 + 明确报错
4. 输入/输出 = 兼容超集：5 标准 key 别名归一化 + timestamp 整型校验保留，其余属性透传；输出含全部输入属性

## 改动清单
运行时表示层：
- 删除 runtime/SequenceEvent.java
- SequenceBlock：5 列数组 → 行式 List<Map<String,Object>>，构造器防御拷贝为不可变 LinkedHashMap；删 eventAtBaseIndex/industryAtBaseIndex/timestampAtBaseIndex；新增 columnValueAt(column, baseIndex) 与 rowAtBaseIndex(baseIndex)
- SequenceValue：删 eventAt；elementAt 返回行 Map；filterByIndustry default → filterByColumn
- SequenceView：filterByIndustry → filterByColumn（按名列比较）；slice/selection 不动
- operator/OperatorSequence：filterByIndustry(String) → filterByColumn(String, Object)（JDK 1.8 安全）
- SequenceIndexRegistry.standard()：INDUSTRY extractor 改为按 "industryId" 列取值

输入/输出边界：
- FeatureInputDecoder：只接受 Map 元素；normalizeEvent 做 5 标准 key 别名归一化（item_id→itemId 等）+ timestamp 整型校验（key 存在时），其余属性透传；产出不可变 Map
- ExternalValueMaterializer：删除 5-key 手工构造，直接返回行 Map（属性全集）

算子层（JDK 1.8 兼容）：
- CalculateDeltaSequenceOperator：Map 元素显式检查 + 明确报错
- ZipConcatOperator：Map 元素显式拒绝 + 明确报错
- find_indices/count_distinct 零代码改动（等式口径变 Map 内容相等，文档写明）

测试：
- DagEngineSelfTest：3 处事件构造改 Map.of；filterByIndustry→filterByColumn；zip_concat 断言改 expectThrows；TestOperatorSequence stub 改签名
- FeatureValueCodecSelfTest：eventAtBaseIndex().industryId() → rowAtBaseIndex().get("industryId")；snake_case 断言扩展
- 新增回归：多属性透传、别名归一化、非法 timestamp 报错、calc_delta_seq/zip_concat 事件输入报错

文档：
- 更新 docs/architecture/sequence-view-operator-support.md 与 operator-optimization-extension.md；docs/superpowers/* 历史文档不动

## 验证与提交
1. ./scripts/run-self-test.sh 全绿（本机需先 export JAVA_HOME/PATH）
2. mvn clean package 编译产物验证
3. 建议先单独提交当前工作区未提交的 sequence-view 落地改动，本改造作为独立主题提交

## 风险（已接受）
- 输出契约 5 key → 属性全集（超集，不破坏下游 5-key 解析）
- count_distinct/find_indices 等式口径变全属性相等
- 行式存储放弃列式性能（view 仍零拷贝）
- supportsSequenceView 标志不动（容器协议与元素类型无关）