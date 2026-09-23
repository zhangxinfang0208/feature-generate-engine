# 本轮优化后的候选热点

依据 `zgc-hotspot-optimization-20260924.md` 的 8 GiB 优化后采样和当前源码。以下是待实验验证的建议，没有测得额外加速比；本 PR 的实测收益仅来自整数加乘及输出错误文本优化。

| 优先级 | 位置与证据 | 候选改法 | 必须保持的语义 |
|---|---|---|---|
| 1，改动较小 | `SliceByIndicesOperator.slice` 的局部 `result` 经 `OperatorSupport.immutableList` 再拷贝一次；整个 slice 调用栈约占分配权重 11.07%，CPU leaf 5.73% | 对算子内部新建、未泄露的列表直接作不可变包装，消除这一次复制；先单项 A/B，再看整体收益。11.07% 包含切片本身等分配，不能全算可节省量 | 重复下标及顺序、完整边界验证、返回列表不可变；不能把调用方传入的列表当作独占结果 |
| 2，需梳理失败传播 | `DagRuntime.evaluateBatch` 先对每行每列调用 `argumentAt` 检查失败，随后 Kernel 再取参数；CPU leaf 11.38%。`firstBatchFailure` 又在输出默认值处理时扫描向量（leaf 3.55%） | 评估在不可变批值构造时记录可信的“是否含失败”元数据，让已确认成功的批次跳过重复扫描；有 default 时还可评估合并失败检查与替换遍历 | 扩展 Kernel 结果必须验证；保留跨组投影、最早失败位置、默认值替换数量与逐行恢复。不能仅凭算子 deterministic 标志推断不会失败 |
| 3，先明确参数所有权 | `SingleLoopBatchOperatorKernel` 每行新建 ArrayList 和参数数组；CPU leaf 9.77%，包含该调用栈的分配权重 32.74%（含所有下游算子，不能视为纯适配成本） | 测量参数容器所占独立权重后，评估只供明确不持有参数的内置 Kernel 使用的轻量适配协议 | 当前 `SingleOperatorKernel` 没有禁止保存/修改参数 List，不能直接复用同一个可变 List 给所有行，否则扩展算子可能观察到后续行的数据 |
| 4，收益需单测 | `CandidateVectorValue` 构造器总是防御复制；约 5.47% 分配权重经过该构造器。`ExternalValueMaterializer` 处理短列表时创建 stream 流水线，其调用栈占分配权重约 9.10% | 评估内部可信的不可变向量构造入口；对短列表比较预分配循环与 stream 实现 | 公共构造器保留防御复制，嵌套值仍正确物化并支持 null；避免把可变扩展结果直接透传 |

输出 Map 仍值得关注，但不是简单重复预分配的问题：`HashMap.putVal` 的 184 个 leaf 样本中，142 个最内层应用调用者是 `FeatureDagEngine` 的在线编码循环（约占全部 1,239 个请求 CPU 样本的 11.46%）。当前 `candidateOutputCapacity` 已用于初始化每货 Map，且结果使用 `fromOwnedEncodedValues`，前一版的二次复制也已消除。当前接口要求返回 500 × 320 个 Map 项，其节点分配有实际输出规模原因。共享键布局或按需 Map 视图属于更大范围的结果表示改动，必须专门评估 `Map` 访问/迭代兼容性、不可变性、序列化和 retained heap，不能直接删掉集合构造。

建议先验证第 1 项，再处理成功批次重复扫描。每项保持同一数据分布、JDK、8 GiB 堆和请求边界，独立测至少三轮；保留逐行 oracle、失败恢复和公共 API 测试。采样占比会随其他热点下降而上升，比例高并不证明该路径的绝对耗时变差。
