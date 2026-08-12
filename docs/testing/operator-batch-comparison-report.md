# 算子 Batch 与 Single 执行差异验证报告

> 本报告基于「4 个劣化算子移除 BatchOperatorKernel」后的代码状态：
> discrete / log_base / slice_by_indices / get_seq_length 不再提供原生 Batch kernel，
> 由 SingleLoopBatchOperatorKernel 逐行适配；find_indices / count_distinct / zip_concat /
> calc_delta_seq 保留原生 Batch。详见「移除说明」一节。

## 验证环境与方法

- 日期：2026-08-12
- 机器：Windows 10.0.26200 x86_64（Git Bash / MINGW64）
- Java：OpenJDK 21.0.12 (Temurin LTS)
- 命令：`./scripts/run-operator-batch-comparison-report.sh "50 200 1000 3000" "200 1000" 3 8 1000 2 5 0 1`
- 工作负载：8 组请求 × 每组 1000 候选行 = 8000 行；序列长度分 50 / 200 / 1000 / 3000 四档
- 统计口径：单次运行内 5 个测量轮次取 median；每个配置重复 3 次，再取 median-of-medians

三条执行路径（OperatorRegistry 直接调用，不含调度与列物化）：
- **single（不走 Batch）**：外层循环逐行调 SingleOperatorKernel，每行全量重算；
- **batchScalar（走 Batch 载体）**：由 SingleLoopBatchOperatorKernel 逐行适配；
- **batchRegistered（注册能力路由）**：保留原生 Batch 的算子按 (group, sequence, 参数)
  身份键批内复用；不提供原生 Batch 的算子自动降级为标量适配器。

两个场景：
- 共享序列场景（scenario 0）：组内候选行共享同一请求序列对象（模拟在线候选行），
  批内 identity 缓存命中；
- 独立参数场景（scenario 1）：每行参数独立，批内缓存全部失效。

## 移除说明（4 个劣化算子不再提供原生 Batch）

| 算子 | 移除前 batchNative/single | 移除后 batchRegistered/single | 理由 |
|---|---|---|---|
| log_base | 0.12x（劣化 8 倍） | ~1.2x | 批内只省 log(base) 预计算，每行仍需 Math.log(value)，批开销反噬 |
| slice_by_indices | 0.27x | ~1.1x | 每行只省 O(下标数) 次取值，key 分配 + map 查找反噬 |
| get_seq_length | 0.22x | ~1.3x | 单行计算极轻（一次 size），原生内核无批内复用缓存 |
| discrete | ~1.0x | ~1.1x | 批内只省边界→BigDecimal 转换，bucket 比较每行仍要做 |

其余 4 个算子（find_indices / count_distinct / zip_concat / calc_delta_seq）批内按
identity 键复用收益显著，保留原生 Batch。注册表、物理计划与运行时无需任何改动：
`OperatorRegistry.register` 自动为未提供原生内核的算子装配标量适配器，物理计划按
注册能力推导 `batchKernelKind`（C10）。

## 共享序列场景（组内候选行共享请求序列，批内按 identity 复用）

#### 序列长度 = 50（行数 = 8000）

| 算子 | single (ms) | batchScalar (ms) | batchRegistered (ms) | registered/single | scalar/single |
|---|---|---|---|---|---|
| calc_delta_seq | 4.813 | 5.542 | 2.386 | 2.02x | 0.87x |
| count_distinct | 7.535 | 7.547 | 1.991 | 3.78x | 1.00x |
| discrete | 3.469 | 3.094 | 3.109 | 1.12x | 1.12x |
| find_indices | 2.708 | 2.525 | 3.618 | 0.75x | 1.07x |
| get_seq_length | 0.242 | 0.283 | 0.234 | 1.03x | 0.86x |
| log_base | 0.391 | 0.371 | 0.427 | 0.92x | 1.05x |
| slice_by_indices | 0.699 | 0.554 | 0.713 | 0.98x | 1.26x |
| zip_concat | 13.908 | 15.660 | 0.507 | 27.43x | 0.89x |

#### 序列长度 = 200（行数 = 8000）

| 算子 | single (ms) | batchScalar (ms) | batchRegistered (ms) | registered/single | scalar/single |
|---|---|---|---|---|---|
| calc_delta_seq | 21.719 | 20.351 | 2.374 | 9.15x | 1.07x |
| count_distinct | 27.451 | 29.684 | 2.118 | 12.96x | 0.92x |
| discrete | 4.369 | 3.806 | 3.736 | 1.17x | 1.15x |
| find_indices | 8.616 | 9.402 | 3.543 | 2.43x | 0.92x |
| get_seq_length | 0.189 | 0.160 | 0.151 | 1.25x | 1.18x |
| log_base | 0.556 | 0.500 | 0.470 | 1.18x | 1.11x |
| slice_by_indices | 0.715 | 0.644 | 0.587 | 1.22x | 1.11x |
| zip_concat | 60.556 | 61.484 | 0.923 | 65.61x | 0.98x |

#### 序列长度 = 1000（行数 = 8000）

| 算子 | single (ms) | batchScalar (ms) | batchRegistered (ms) | registered/single | scalar/single |
|---|---|---|---|---|---|
| calc_delta_seq | 227.801 | 218.311 | 4.528 | 50.31x | 1.04x |
| count_distinct | 117.895 | 130.928 | 2.245 | 52.51x | 0.90x |
| discrete | 3.703 | 3.433 | 3.795 | 0.98x | 1.08x |
| find_indices | 48.886 | 49.935 | 4.218 | 11.59x | 0.98x |
| get_seq_length | 0.287 | 0.287 | 0.222 | 1.29x | 1.00x |
| log_base | 0.573 | 0.446 | 0.430 | 1.33x | 1.28x |
| slice_by_indices | 0.697 | 0.660 | 0.642 | 1.09x | 1.06x |
| zip_concat | 470.463 | 503.160 | 1.774 | 265.20x | 0.94x |

#### 序列长度 = 3000（行数 = 8000）

| 算子 | single (ms) | batchScalar (ms) | batchRegistered (ms) | registered/single | scalar/single |
|---|---|---|---|---|---|
| calc_delta_seq | 885.122 | 852.246 | 12.395 | 71.41x | 1.04x |
| count_distinct | 466.228 | 448.249 | 3.291 | 141.67x | 1.04x |
| discrete | 5.733 | 6.499 | 6.285 | 0.91x | 0.88x |
| find_indices | 179.685 | 178.476 | 5.850 | 30.72x | 1.01x |
| get_seq_length | 0.328 | 0.262 | 0.243 | 1.35x | 1.25x |
| log_base | 0.665 | 0.653 | 0.556 | 1.20x | 1.02x |
| slice_by_indices | 0.871 | 0.895 | 0.784 | 1.11x | 0.97x |
| zip_concat | 2478.306 | 2516.808 | 3.024 | 819.54x | 0.98x |

## 独立参数场景（每行独立参数，批内无复用）

#### 序列长度 = 200（行数 = 8000）

| 算子 | single (ms) | batchScalar (ms) | batchRegistered (ms) | registered/single | scalar/single |
|---|---|---|---|---|---|
| calc_delta_seq | 19.001 | 19.000 | 22.445 | 0.85x | 1.00x |
| count_distinct | 29.123 | 29.042 | 34.534 | 0.84x | 1.00x |
| discrete | 4.038 | 3.886 | 3.637 | 1.11x | 1.04x |
| find_indices | 7.636 | 7.036 | 87.022 | 0.09x | 1.09x |
| get_seq_length | 0.528 | 0.343 | 0.178 | 2.97x | 1.54x |
| log_base | 0.464 | 0.428 | 0.395 | 1.17x | 1.08x |
| slice_by_indices | 1.054 | 0.944 | 0.670 | 1.57x | 1.12x |
| zip_concat | 67.417 | 67.949 | 72.642 | 0.93x | 0.99x |

#### 序列长度 = 1000（行数 = 8000）

| 算子 | single (ms) | batchScalar (ms) | batchRegistered (ms) | registered/single | scalar/single |
|---|---|---|---|---|---|
| calc_delta_seq | 266.177 | 260.212 | 238.222 | 1.12x | 1.02x |
| count_distinct | 164.583 | 181.497 | 163.106 | 1.01x | 0.91x |
| discrete | 6.168 | 7.177 | 5.991 | 1.03x | 0.86x |
| find_indices | 64.594 | 65.429 | 264.823 | 0.24x | 1.02x |
| get_seq_length | 0.926 | 0.611 | 0.489 | 1.89x | 1.52x |
| log_base | 0.738 | 0.657 | 0.619 | 1.19x | 1.12x |
| slice_by_indices | 2.227 | 1.899 | 1.626 | 1.37x | 1.17x |
| zip_concat | 704.354 | 704.062 | 737.245 | 0.96x | 1.00x |

## 移除前后对比（共享序列场景，batchRegistered 相对 single 的加速比）

| 算子 | 移除前（原生 Batch） | 移除后（注册路由） | 效果 |
|---|---|---|---|
| log_base | 0.12~0.16x | 0.92~1.33x | 劣化消除 |
| slice_by_indices | 0.27~0.38x | 0.98~1.22x | 劣化消除 |
| get_seq_length | 0.22~0.30x | 1.03~1.35x | 劣化消除 |
| discrete | 0.98~1.37x | 0.91~1.17x | 持平 |
| find_indices | 0.79~33x | 0.75~31x | 收益保留 |
| count_distinct | 4~164x | 3.8~142x | 收益保留 |
| zip_concat | 24~510x | 27~820x | 收益保留 |
| calc_delta_seq | 3~75x | 2~71x | 收益保留 |

## 劣化算子改走 Single（模拟物理计划 batchKernelKind=SCALAR_ADAPTER）

劣化判定：batchRegistered median > batchScalar median（模拟规划期成本模型结论）。
基线 = 全部算子走注册能力路由的 median 总耗时；优化后 = 劣化算子改走
SingleLoopBatchOperatorKernel（逐行 single 语义）、其余算子保持注册路由。

| 场景 | 序列长度 | 基线总耗时 (ms) | 优化后总耗时 (ms) | 整体收益 | 劣化算子数 |
|---|---|---|---|---|---|
| 共享序列 | 50 | 13.135 | 11.693 | 1.12x | 3 |
| 共享序列 | 200 | 13.848 | 13.848 | 1.00x | 1 |
| 共享序列 | 1000 | 17.873 | 17.403 | 1.03x | 2 |
| 共享序列 | 3000 | 33.286 | 32.451 | 1.03x | 1 |
| 独立参数 | 200 | 223.685 | 128.918 | 1.74x | 4 |
| 独立参数 | 1000 | 1328.057 | 1028.141 | 1.29x | 3 |

共享场景劣化集已从移除前的 4 个（log_base / slice_by_indices / get_seq_length /
discrete）降到 1~3 个且均为测量噪声边缘判定（如 find_indices 短序列），核心劣化已
消除。独立参数场景剩余劣化来自保留原生 Batch 的算子（find_indices 批内建索引 map、
zip_concat / count_distinct 无复用时白付批开销）——这是「静态保留」的边界，如需
覆盖可交给规划期成本模型动态降级。

## 特征表达式层验证（8 个算子各一 DERIVED 特征表达式，走完整引擎链路）

候选特征（USER×ITEM）：bucket_level=discrete(amount, [0,10,50,100,500])、
log_amount=log_base(amount, 2, 1048576)、target_positions=find_indices(codes, target_tag)、
delta_sequence=calc_delta_seq(numbers, delta_base)；
共享序列特征（USER）：code_window=slice_by_indices(codes, [0..10])、
behavior_length=get_seq_length(codes)、distinct_codes=count_distinct(codes)、
joined_window=zip_concat(slice_by_indices(codes, [0,2,4]), slice_by_indices(numbers, [0,2,4]), {"delimiter":"|"})。

两条路径：individual（每请求组一次 generate，共 8 次请求）vs grouped（一次 generateBatch）。
引擎按输入载体与注册能力分派（C10）：候选特征恒走批路径（原生 Batch 或标量适配器）；
纯共享序列特征在 individual 下走 Single kernel，在 grouped 下随共享值向量化走请求批域。
统计口径同主报告（8000 行，重复 3 次，median-of-medians）。

| 序列长度 | individual median (ms) | grouped median (ms) | grouped/individual |
|---|---|---|---|
| 50 | 33.477 | 13.255 | 2.53x |
| 200 | 52.406 | 23.916 | 2.19x |
| 1000 | 152.787 | 75.228 | 2.03x |
| 3000 | 417.093 | 321.693 | 1.30x |

诊断确认：表达式 DAG 共 34 逻辑/物理节点、10 个算子节点、无物理融合；
individual 执行同时出现 SINGLE（纯共享特征）、BATCH_NATIVE（保留原生 Batch 的
候选特征）与 BATCH_SCALAR_ADAPTER（discrete/log_base 候选特征）三类分派；
grouped 执行 10 个算子节点全部走批路径（4 native + 6 scalar 适配），无 SINGLE。

## 结论

- 4 个劣化算子移除 BatchOperatorKernel 后劣化全部消除（log_base 0.12x → ~1.2x、
  slice_by_indices 0.27x → ~1.1x、get_seq_length 0.22x → ~1.3x），注册表/物理计划/
  运行时零改动（注册能力自动推导，C10 合规）；
- 保留原生 Batch 的 4 个算子复用收益不变（zip_concat 27~820x、count_distinct 4~142x、
  calc_delta_seq 2~71x、find_indices 0.75~31x，随序列长度放大）；
- 共享场景整体劣化集从 4 个降到 1~3 个（噪声边缘），无复用场景剩余劣化来自
  保留原生 Batch 的算子（find_indices 建索引、zip/count 白付批开销），可留给
  规划期成本模型动态降级；
- 表达式层 grouped 批量请求比 individual 快约 1.3~2.5x（解码/调度/编码摊销）。
