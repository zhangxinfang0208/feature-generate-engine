package com.example.featuredag.demo;

import com.example.featuredag.operator.BatchColumn;
import com.example.featuredag.operator.BatchDomain;
import com.example.featuredag.operator.BatchKernelKind;
import com.example.featuredag.operator.BatchLayout;
import com.example.featuredag.operator.BatchOperatorCall;
import com.example.featuredag.operator.BatchOperatorResult;
import com.example.featuredag.operator.ListBatchColumn;
import com.example.featuredag.operator.OperatorRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 算子 kernel 层对比 demo：对首期全部 8 个算子，同一批在线候选行
 * 「走算子 Batch」与「不走 Batch」的耗时差异。
 *
 * <p>三条执行路径（均为 OperatorRegistry 直接调用，不含调度与列物化开销）：
 * <ul>
 *   <li>{@code single}：不走 Batch，外层循环逐行调 SingleOperatorKernel，每行全量重算；</li>
 *   <li>{@code batchScalar}：走 Batch 载体但由 SingleLoopBatchOperatorKernel 逐行适配；</li>
 *   <li>{@code batchRegistered}：走注册能力路由——保留原生 Batch 的算子
 *       （find_indices/count_distinct/zip_concat/calc_delta_seq）按 identity 键批内复用，
 *       不提供原生 Batch 的算子（discrete/log_base/slice_by_indices/get_seq_length）
 *       自动降级为标量适配器。</li>
 * </ul>
 * 默认场景模拟在线请求：组内候选行共享同一条请求序列对象（与真实引擎一致），
 * 原生 Batch 的批内缓存按 identity 命中；{@code distinctParams=1} 时每行参数独立，
 * 批内缓存全部失效，用于观察无复用场景下 Batch 通路的真实开销。
 *
 * <p>8 个算子中 get_seq_length 的原生 Batch 内核没有复用缓存（见
 * {@code GetSequenceLengthOperator.evaluateBatch}），作为对照项展示
 * 「走了 Batch 但无复用」≈ Single 的水平。
 */
public final class OperatorBatchComparisonDemo {
    private static final String DISCRETE = "discrete";
    private static final String LOG_BASE = "log_base";
    private static final String SLICE_BY_INDICES = "slice_by_indices";
    private static final String FIND_INDICES = "find_indices";
    private static final String GET_SEQ_LENGTH = "get_seq_length";
    private static final String COUNT_DISTINCT = "count_distinct";
    private static final String ZIP_CONCAT = "zip_concat";
    private static final String CALC_DELTA_SEQ = "calc_delta_seq";

    /** 默认场景 log_base 的 base 取值域宽度（值域为 2..16，避免触发 base==1 校验）。 */
    private static final int BASE_DOMAIN_WIDTH = 15;

    private OperatorBatchComparisonDemo() {}

    public static void runSmokeTest() {
        run(new Options(8, 2, 4, 1, 1, false, false, false), false);
    }

    public static void main(String[] args) {
        run(Options.parse(args), true);
    }

    private static void run(Options options, boolean printReport) {
        Workload workload = new Workload(options);
        List<Measurements> measurements = new ArrayList<Measurements>(
                workload.operatorCases.size());
        for (int index = 0; index < workload.operatorCases.size(); index++) {
            measurements.add(new Measurements(options.measurementRounds));
        }

        // 预热轮同时断言三条路径逐行等价；测量轮不再重复断言。
        for (int round = 0; round < options.warmupRounds; round++) {
            for (int index = 0; index < workload.operatorCases.size(); index++) {
                executeSingle(workload, index, true);
                executeBatch(workload, index, true);
                executeBatch(workload, index, false);
            }
        }
        for (int round = 0; round < options.measurementRounds; round++) {
            for (int index = 0; index < workload.operatorCases.size(); index++) {
                long singleStart = System.nanoTime();
                executeSingle(workload, index, false);
                measurements.get(index).singleDurations[round] =
                        System.nanoTime() - singleStart;

                long scalarStart = System.nanoTime();
                executeBatch(workload, index, true);
                measurements.get(index).scalarAdapterDurations[round] =
                        System.nanoTime() - scalarStart;

                long nativeStart = System.nanoTime();
                executeBatch(workload, index, false);
                measurements.get(index).nativeBatchDurations[round] =
                        System.nanoTime() - nativeStart;
            }
        }
        if (printReport) {
            printReport(options, workload, measurements);
        }
    }

    /**
     * 劣化优化模拟：用每算子三路径的 median 合成「全走注册能力路由」与
     * 「registered 劣化的算子改走 SCALAR_ADAPTER」两组总耗时，量化整体收益。
     * 劣化判定 = batchRegistered median &gt; batchScalar median（模拟规划期成本模型）。
     */
    private static void printDegradedOptimization(
            Workload workload,
            List<Measurements> measurements) {
        List<String> degraded = new ArrayList<String>();
        long baselineTotal = 0;
        long optimizedTotal = 0;
        for (int index = 0; index < workload.operatorCases.size(); index++) {
            OperatorCase operatorCase = workload.operatorCases.get(index);
            Measurements perCase = measurements.get(index);
            long nativeMedian = percentile(
                    sorted(perCase.nativeBatchDurations), 0.50);
            long scalarMedian = percentile(
                    sorted(perCase.scalarAdapterDurations), 0.50);
            long singleMedian = percentile(
                    sorted(perCase.singleDurations), 0.50);
            baselineTotal += nativeMedian;
            boolean isDegraded = nativeMedian > scalarMedian;
            if (isDegraded) degraded.add(operatorCase.name);
            optimizedTotal += isDegraded ? scalarMedian : nativeMedian;
            System.out.println("  " + operatorCase.name
                    + ": registered=" + millis(nativeMedian)
                    + ", scalar=" + millis(scalarMedian)
                    + ", single=" + millis(singleMedian)
                    + (isDegraded ? "  <- 劣化, 改走 scalar" : ""));
        }
        System.out.println("劣化集: " + degraded);
        System.out.println("基线总耗时(全注册路由) = " + millis(baselineTotal) + " ms");
        System.out.println("优化后总耗时(劣化改 scalar) = " + millis(optimizedTotal) + " ms");
        System.out.println("整体收益 = " + ratio(baselineTotal, optimizedTotal));
    }

    /** 不走 Batch：逐行调 single kernel，并断言与原生 Batch 结果逐行一致。 */
    private static void executeSingle(Workload workload, int caseIndex, boolean assertEquivalence) {
        OperatorCase operatorCase = workload.operatorCases.get(caseIndex);
        List<Object> singleResults = new ArrayList<Object>(workload.rowCount);
        for (int rowIndex = 0; rowIndex < workload.rowCount; rowIndex++) {
            singleResults.add(workload.registry.evaluate(
                    operatorCase.name, operatorCase.argumentsAt(rowIndex)));
        }
        if (assertEquivalence) {
            assertEquivalent(operatorCase.name, singleResults,
                    workload.evaluateBatch(operatorCase, false));
        }
    }

    /** 走 Batch：一次 evaluateBatch；{@code scalarAdapter} 为 true 时强制逐行适配内核。 */
    private static void executeBatch(Workload workload, int caseIndex, boolean scalarAdapter) {
        OperatorCase operatorCase = workload.operatorCases.get(caseIndex);
        BatchOperatorResult result = workload.evaluateBatch(operatorCase, scalarAdapter);
        if (result.values().size() != workload.rowCount) {
            throw new IllegalStateException(
                    "Batch result row count mismatch for " + operatorCase.name);
        }
    }

    private static void assertEquivalent(
            String operatorName,
            List<Object> singleResults,
            BatchOperatorResult batchResult) {
        if (singleResults.size() != batchResult.values().size()) {
            throw new IllegalStateException(
                    "Result size mismatch for " + operatorName);
        }
        for (int rowIndex = 0; rowIndex < singleResults.size(); rowIndex++) {
            Object expected = singleResults.get(rowIndex);
            Object actual = batchResult.values().valueAt(rowIndex);
            if (!expected.equals(actual)) {
                throw new IllegalStateException(
                        "Row " + rowIndex + " mismatch for " + operatorName
                                + ": single=" + expected + ", batch=" + actual);
            }
        }
    }

    private static void printReport(
            Options options,
            Workload workload,
            List<Measurements> measurements) {
        System.out.println("=== OPERATOR BATCH VS SINGLE COMPARISON (8 OPERATORS) ===");
        System.out.println("scenario=" + (options.distinctParams
                ? "independent-parameters(每行独立参数, 批内无复用)"
                : options.dualSequence
                        ? "dual-sequence-features(每组共享两个序列特征 seqA+seqB, 批内按 identity 复用)"
                        : "shared-sequences(组内候选行共享请求序列, 批内按 identity 复用)"));
        System.out.println("sequenceLength=" + options.sequenceLength
                + ", groups=" + options.groupCount
                + ", candidatesPerGroup=" + options.candidatesPerGroup
                + ", totalRows=" + workload.rowCount
                + ", warmups=" + options.warmupRounds
                + ", measurements=" + options.measurementRounds);

        System.out.println(String.format(Locale.ROOT,
                "%-15s %-13s %8s %10s %8s %12s %9s",
                "operator", "path", "minMs", "medianMs", "p95Ms", "rows/s", "vsSingle"));
        for (int index = 0; index < workload.operatorCases.size(); index++) {
            OperatorCase operatorCase = workload.operatorCases.get(index);
            Measurements perCase = measurements.get(index);
            long[] sortedSingle = sorted(perCase.singleDurations);
            long[] sortedScalar = sorted(perCase.scalarAdapterDurations);
            long[] sortedNative = sorted(perCase.nativeBatchDurations);
            long singleMedian = percentile(sortedSingle, 0.50);
            printTimingRow(operatorCase.name, "single",
                    sortedSingle, workload.rowCount);
            printTimingRow(operatorCase.name, "batchScalar",
                    sortedScalar, workload.rowCount, singleMedian);
            printTimingRow(operatorCase.name, "batchRegistered",
                    sortedNative, workload.rowCount, singleMedian);
            String note = operatorCase.reuseNote;
            System.out.println("  -> " + operatorCase.name
                    + " batchRegistered/single="
                    + ratio(singleMedian, percentile(sortedNative, 0.50))
                    + ", batchScalar/single="
                    + ratio(singleMedian, percentile(sortedScalar, 0.50))
                    + ", 批内理论复用上限=" + operatorCase.reuseBound + "x"
                    + (note.isEmpty() ? "" : " (" + note + ")"));
        }
        if (options.optimizeDegraded) {
            System.out.println();
            System.out.println("=== 劣化算子改走 Single（模拟物理计划 batchKernelKind=SCALAR_ADAPTER）===");
            System.out.println("劣化判定：batchNative median > batchScalar median");
            printDegradedOptimization(workload, measurements);
        }
    }

    private static void printTimingRow(
            String operatorName,
            String pathName,
            long[] sortedDurations,
            int rowCount) {
        printTimingRow(operatorName, pathName, sortedDurations, rowCount, -1L);
    }

    private static void printTimingRow(
            String operatorName,
            String pathName,
            long[] sortedDurations,
            int rowCount,
            long singleMedian) {
        long median = percentile(sortedDurations, 0.50);
        double throughput = rowCount * 1_000_000_000.0 / median;
        String vsSingle = singleMedian < 0 ? "-" : ratio(singleMedian, median);
        System.out.println(String.format(Locale.ROOT,
                "%-15s %-13s %8s %10s %8s %12.2f %9s",
                operatorName,
                pathName,
                millis(sortedDurations[0]),
                millis(median),
                millis(percentile(sortedDurations, 0.95)),
                throughput,
                vsSingle));
    }

    private static String ratio(long singleMedian, long pathMedian) {
        if (pathMedian <= 0) return "n/a";
        return String.format(Locale.ROOT, "%.2fx",
                (double) singleMedian / pathMedian);
    }

    private static long[] sorted(long[] durations) {
        long[] copy = durations.clone();
        Arrays.sort(copy);
        return copy;
    }

    private static long percentile(long[] sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    /** 每个算子在测量轮次内的三条路径耗时（ns）。 */
    private static final class Measurements {
        private final long[] singleDurations;
        private final long[] scalarAdapterDurations;
        private final long[] nativeBatchDurations;

        private Measurements(int rounds) {
            this.singleDurations = new long[rounds];
            this.scalarAdapterDurations = new long[rounds];
            this.nativeBatchDurations = new long[rounds];
        }
    }

    /**
     * 演示工作负载：{@code groupCount} 组请求，每组共享一条长度为
     * {@code sequenceLength} 的请求序列，每组 {@code candidatesPerGroup} 个候选行。
     * 行按组主序排列（与运行时在线候选行批布局一致，见 BatchLayout.groupIndexAt）。
     */
    private static final class Workload {
        private final OperatorRegistry registry = OperatorRegistry.standard();
        private final int rowCount;
        private final List<OperatorCase> operatorCases;

        private Workload(Options options) {
            int groups = options.groupCount;
            int candidates = options.candidatesPerGroup;
            this.rowCount = groups * candidates;

            List<List<String>> groupCodes = new ArrayList<List<String>>(groups);
            List<List<String>> groupTags = new ArrayList<List<String>>(groups);
            List<List<Double>> groupNumbers = new ArrayList<List<Double>>(groups);
            for (int groupIndex = 0; groupIndex < groups; groupIndex++) {
                groupCodes.add(sequenceOfCodes(options.sequenceLength, groupIndex));
                groupTags.add(sequenceOfTags(options.sequenceLength, groupIndex));
                groupNumbers.add(sequenceOfNumbers(options.sequenceLength, groupIndex));
            }
            // discrete 边界与 slice 下标在全批共享同一对象，模拟特征定义中的常量参数
            List<Double> sharedBoundaries = discreteBoundaries();
            List<Integer> sharedIndices = sliceIndices(options.sequenceLength);

            CandidateRowLayout layout = new CandidateRowLayout(groups, candidates);
            int sequenceReuseBound = options.distinctParams ? 1 : candidates;
            int globalReuseBound = options.distinctParams ? 1 : rowCount;
            operatorCases = new ArrayList<OperatorCase>(8);
            operatorCases.add(discreteCase(options, layout, groupNumbers,
                    sharedBoundaries, globalReuseBound));
            operatorCases.add(logBaseCase(options, layout, groupNumbers,
                    options.distinctParams ? 1 : BASE_DOMAIN_WIDTH));
            operatorCases.add(sliceByIndicesCase(options, layout, groupCodes,
                    sharedIndices, sequenceReuseBound));
            operatorCases.add(findIndicesCase(
                    options, layout, groupCodes, sequenceReuseBound));
            // 双序列特征模式下，两个共享序列特征（seqA 字符串 / seqB 数值）都被算子消费；
            // get_seq_length 改取 seqB，zip_concat 直接拼接 seqA+seqB
            List<? extends List<?>> lengthSequences;
            List<? extends List<?>> zipSecondSequences;
            if (options.dualSequence) {
                lengthSequences = groupNumbers;
                zipSecondSequences = groupNumbers;
            } else {
                lengthSequences = groupCodes;
                zipSecondSequences = groupTags;
            }
            operatorCases.add(getSequenceLengthCase(
                    options, layout, lengthSequences));
            operatorCases.add(countDistinctCase(
                    options, layout, groupCodes, sequenceReuseBound));
            operatorCases.add(zipConcatCase(options, layout, groupCodes,
                    zipSecondSequences, sequenceReuseBound));
            operatorCases.add(calcDeltaSequenceCase(
                    options, layout, groupNumbers, sequenceReuseBound));
        }

        /**
         * discrete：批内按边界对象身份缓存 BigDecimal 转换结果（key 不区分请求组），
         * 每行仍要做 bucket 比较；复用上限为全批行数。
         */
        private OperatorCase discreteCase(
                Options options,
                CandidateRowLayout layout,
                List<List<Double>> groupNumbers,
                List<Double> sharedBoundaries,
                int reuseBound) {
            List<BatchColumn> argumentColumns = new ArrayList<BatchColumn>(2);
            argumentColumns.add(column(layout, new SequenceArgumentProvider() {
                @Override
                public Object valueAt(int rowIndex) {
                    List<Double> numbers = groupNumbers.get(layout.groupIndexAt(rowIndex));
                    return numbers.get(layout.indexInGroupAt(rowIndex) % numbers.size());
                }
            }));
            argumentColumns.add(column(layout, new SequenceArgumentProvider() {
                @Override
                public Object valueAt(int rowIndex) {
                    if (!options.distinctParams) return sharedBoundaries;
                    // 独立参数模式：复制为新对象使 identity 缓存失效
                    return new ArrayList<Double>(sharedBoundaries);
                }
            }));
            return new OperatorCase(DISCRETE, layout, argumentColumns,
                    reuseBound, options.distinctParams ? "" : "边界常量全批共享");
        }

        /** log_base：批内按 (base, upbound) 值键缓存预计算参数，复用上限为 base 值域宽度。 */
        private OperatorCase logBaseCase(
                Options options,
                CandidateRowLayout layout,
                List<List<Double>> groupNumbers,
                int reuseBound) {
            List<BatchColumn> argumentColumns = new ArrayList<BatchColumn>(3);
            argumentColumns.add(column(layout, new SequenceArgumentProvider() {
                @Override
                public Object valueAt(int rowIndex) {
                    List<Double> numbers = groupNumbers.get(layout.groupIndexAt(rowIndex));
                    return numbers.get(layout.indexInGroupAt(rowIndex) % numbers.size());
                }
            }));
            argumentColumns.add(column(layout, new SequenceArgumentProvider() {
                @Override
                public Object valueAt(int rowIndex) {
                    return parameterBase(options, rowIndex);
                }
            }));
            argumentColumns.add(column(layout, new SequenceArgumentProvider() {
                @Override
                public Object valueAt(int rowIndex) {
                    return 1000.0;
                }
            }));
            return new OperatorCase(LOG_BASE, layout, argumentColumns, reuseBound);
        }

        /** slice_by_indices：批内按 (group, sequence, indices) 键复用整个切片结果。 */
        private OperatorCase sliceByIndicesCase(
                Options options,
                CandidateRowLayout layout,
                List<List<String>> groupCodes,
                List<Integer> sharedIndices,
                int reuseBound) {
            List<BatchColumn> argumentColumns = new ArrayList<BatchColumn>(2);
            argumentColumns.add(column(layout, new SequenceArgumentProvider() {
                @Override
                public Object valueAt(int rowIndex) {
                    List<String> shared = groupCodes.get(layout.groupIndexAt(rowIndex));
                    if (!options.distinctParams) return shared;
                    return new ArrayList<String>(shared);
                }
            }));
            argumentColumns.add(column(layout, new SequenceArgumentProvider() {
                @Override
                public Object valueAt(int rowIndex) {
                    if (!options.distinctParams) return sharedIndices;
                    return new ArrayList<Integer>(sharedIndices);
                }
            }));
            return new OperatorCase(SLICE_BY_INDICES, layout, argumentColumns, reuseBound);
        }

        /**
         * find_indices：批内按 (group, sequence) 键缓存「元素→下标」索引，
         * 目标值每行变化仍只需 O(1) 查表，省掉每行 O(序列长度) 扫描。
         */
        private OperatorCase findIndicesCase(
                Options options,
                CandidateRowLayout layout,
                List<List<String>> groupCodes,
                int reuseBound) {
            List<BatchColumn> argumentColumns = new ArrayList<BatchColumn>(2);
            argumentColumns.add(column(layout, new SequenceArgumentProvider() {
                @Override
                public Object valueAt(int rowIndex) {
                    List<String> shared = groupCodes.get(layout.groupIndexAt(rowIndex));
                    if (!options.distinctParams) return shared;
                    return new ArrayList<String>(shared);
                }
            }));
            argumentColumns.add(column(layout, new SequenceArgumentProvider() {
                @Override
                public Object valueAt(int rowIndex) {
                    // 目标值每行不同，但 batch 内核只查索引表
                    return "code-" + ((layout.indexInGroupAt(rowIndex) * 3
                            + layout.groupIndexAt(rowIndex)) % 128);
                }
            }));
            return new OperatorCase(FIND_INDICES, layout, argumentColumns, reuseBound);
        }

        /** get_seq_length：原生 Batch 内核无复用缓存（对照项），理论复用上限恒为 1x。 */
        private OperatorCase getSequenceLengthCase(
                Options options,
                CandidateRowLayout layout,
                List<? extends List<?>> groupSequences) {
            List<BatchColumn> argumentColumns = new ArrayList<BatchColumn>(1);
            argumentColumns.add(column(layout, new SequenceArgumentProvider() {
                @Override
                public Object valueAt(int rowIndex) {
                    List<?> shared = groupSequences.get(layout.groupIndexAt(rowIndex));
                    if (!options.distinctParams) return shared;
                    return new ArrayList<Object>(shared);
                }
            }));
            return new OperatorCase(GET_SEQ_LENGTH, layout, argumentColumns, 1,
                    "内核无复用缓存");
        }

        /** count_distinct：批内按 (group, sequence) 身份键复用，复用上限为组内候选行数。 */
        private OperatorCase countDistinctCase(
                Options options,
                CandidateRowLayout layout,
                List<List<String>> groupCodes,
                int reuseBound) {
            List<BatchColumn> argumentColumns = new ArrayList<BatchColumn>(1);
            argumentColumns.add(column(layout, new SequenceArgumentProvider() {
                @Override
                public Object valueAt(int rowIndex) {
                    List<String> shared = groupCodes.get(layout.groupIndexAt(rowIndex));
                    if (!options.distinctParams) return shared;
                    return new ArrayList<String>(shared);
                }
            }));
            return new OperatorCase(COUNT_DISTINCT, layout, argumentColumns, reuseBound);
        }

        /** zip_concat：批内按 (group, sequences, 分隔符) 键复用整个拼接结果。 */
        private OperatorCase zipConcatCase(
                Options options,
                CandidateRowLayout layout,
                List<? extends List<?>> firstSequences,
                List<? extends List<?>> secondSequences,
                int reuseBound) {
            List<BatchColumn> argumentColumns = new ArrayList<BatchColumn>(2);
            argumentColumns.add(column(layout, new SequenceArgumentProvider() {
                @Override
                public Object valueAt(int rowIndex) {
                    List<?> shared = firstSequences.get(layout.groupIndexAt(rowIndex));
                    if (!options.distinctParams) return shared;
                    return new ArrayList<Object>(shared);
                }
            }));
            argumentColumns.add(column(layout, new SequenceArgumentProvider() {
                @Override
                public Object valueAt(int rowIndex) {
                    return secondSequences.get(layout.groupIndexAt(rowIndex));
                }
            }));
            return new OperatorCase(ZIP_CONCAT, layout, argumentColumns, reuseBound);
        }

        /** calc_delta_seq：批内按 (group, sequence, base) 键复用，复用上限为组内候选行数。 */
        private OperatorCase calcDeltaSequenceCase(
                Options options,
                CandidateRowLayout layout,
                List<List<Double>> groupNumbers,
                int reuseBound) {
            List<BatchColumn> argumentColumns = new ArrayList<BatchColumn>(2);
            argumentColumns.add(column(layout, new SequenceArgumentProvider() {
                @Override
                public Object valueAt(int rowIndex) {
                    List<Double> shared = groupNumbers.get(layout.groupIndexAt(rowIndex));
                    if (!options.distinctParams) return shared;
                    return new ArrayList<Double>(shared);
                }
            }));
            argumentColumns.add(column(layout, new SequenceArgumentProvider() {
                @Override
                public Object valueAt(int rowIndex) {
                    return parameterBase(options, rowIndex);
                }
            }));
            return new OperatorCase(CALC_DELTA_SEQ, layout, argumentColumns, reuseBound);
        }

        /** 默认场景 base 取自小组值域（批内可复用）；独立参数模式每行唯一。 */
        private static double parameterBase(Options options, int rowIndex) {
            if (options.distinctParams) return 2.0 + rowIndex;
            return (rowIndex % BASE_DOMAIN_WIDTH) + 2.0;
        }

        private static List<String> sequenceOfCodes(int length, int groupIndex) {
            List<String> codes = new ArrayList<String>(length);
            for (int index = 0; index < length; index++) {
                codes.add("code-" + ((index + groupIndex) % 128));
            }
            return Collections.unmodifiableList(codes);
        }

        private static List<String> sequenceOfTags(int length, int groupIndex) {
            List<String> tags = new ArrayList<String>(length);
            for (int index = 0; index < length; index++) {
                tags.add("tag-" + ((index + groupIndex) % 64));
            }
            return Collections.unmodifiableList(tags);
        }

        private static List<Double> sequenceOfNumbers(int length, int groupIndex) {
            List<Double> numbers = new ArrayList<Double>(length);
            for (int index = 0; index < length; index++) {
                numbers.add(index + groupIndex + 1.0);
            }
            return Collections.unmodifiableList(numbers);
        }

        /** discrete 边界常量（严格递增，模拟特征定义中的常量参数）。 */
        private static List<Double> discreteBoundaries() {
            List<Double> boundaries = new ArrayList<Double>(5);
            boundaries.add(10.0);
            boundaries.add(50.0);
            boundaries.add(100.0);
            boundaries.add(200.0);
            boundaries.add(500.0);
            return Collections.unmodifiableList(boundaries);
        }

        /** slice_by_indices 下标常量（模拟特征定义中的常量参数，且不超出序列长度）。 */
        private static List<Integer> sliceIndices(int sequenceLength) {
            List<Integer> indices = new ArrayList<Integer>(6);
            for (int index = 0; index < sequenceLength && indices.size() < 6; index += 2) {
                indices.add(index);
            }
            return Collections.unmodifiableList(indices);
        }

        private static BatchColumn column(
                BatchLayout layout,
                SequenceArgumentProvider provider) {
            List<Object> values = new ArrayList<Object>(layout.rowCount());
            for (int rowIndex = 0; rowIndex < layout.rowCount(); rowIndex++) {
                values.add(provider.valueAt(rowIndex));
            }
            return new ListBatchColumn(values);
        }

        private BatchOperatorResult evaluateBatch(OperatorCase operatorCase, boolean scalarAdapter) {
            // 注册能力路由：保留原生 Batch 的算子走 NATIVE；
            // 不提供原生 Batch 的（discrete/log_base/slice_by_indices/get_seq_length）
            // 自动降级为标量适配器，行为与 batchScalar 一致
            BatchKernelKind plannedKind = scalarAdapter
                    ? BatchKernelKind.SCALAR_ADAPTER
                    : registry.batchKernelKind(operatorCase.name);
            return registry.evaluateBatch(operatorCase.name, operatorCase.call, plannedKind);
        }
    }

    /** 单个算子的批调用与逐行参数构造。 */
    private static final class OperatorCase {
        private final String name;
        private final BatchOperatorCall call;
        private final int reuseBound;
        private final String reuseNote;

        private OperatorCase(
                String name,
                BatchLayout layout,
                List<BatchColumn> argumentColumns,
                int reuseBound) {
            this(name, layout, argumentColumns, reuseBound, "");
        }

        private OperatorCase(
                String name,
                BatchLayout layout,
                List<BatchColumn> argumentColumns,
                int reuseBound,
                String reuseNote) {
            this.name = name;
            this.call = new BatchOperatorCall(layout, argumentColumns);
            this.reuseBound = reuseBound;
            this.reuseNote = reuseNote;
        }

        /** 不走 Batch 的逐行参数（每行新建参数列表，模拟单值调度路径）。 */
        private List<Object> argumentsAt(int rowIndex) {
            List<Object> arguments = new ArrayList<Object>(call.arguments().size());
            for (BatchColumn column : call.arguments()) {
                arguments.add(column.valueAt(rowIndex));
            }
            return arguments;
        }
    }

    /** 行级值提供者；JDK 8 兼容接口避免 demo 依赖更高版本语言特性。 */
    private interface SequenceArgumentProvider {
        Object valueAt(int rowIndex);
    }

    /** 演示用批布局：组主序排列候选行，映射回 (groupIndex, indexInGroup)。 */
    private static final class CandidateRowLayout implements BatchLayout {
        private final int rowCount;
        private final int candidatesPerGroup;

        private CandidateRowLayout(int groupCount, int candidatesPerGroup) {
            this.rowCount = groupCount * candidatesPerGroup;
            this.candidatesPerGroup = candidatesPerGroup;
        }

        @Override
        public BatchDomain domain() {
            return BatchDomain.ONLINE_CANDIDATE;
        }

        @Override
        public int rowCount() {
            return rowCount;
        }

        @Override
        public int groupIndexAt(int rowIndex) {
            return rowIndex / candidatesPerGroup;
        }

        @Override
        public int indexInGroupAt(int rowIndex) {
            return rowIndex % candidatesPerGroup;
        }
    }

    private static final class Options {
        private static final int DEFAULT_SEQUENCE_LENGTH = 200;
        private static final int DEFAULT_GROUP_COUNT = 8;
        private static final int DEFAULT_CANDIDATES_PER_GROUP = 1000;
        private static final int DEFAULT_WARMUP_ROUNDS = 2;
        private static final int DEFAULT_MEASUREMENT_ROUNDS = 5;

        private final int sequenceLength;
        private final int groupCount;
        private final int candidatesPerGroup;
        private final int warmupRounds;
        private final int measurementRounds;
        private final boolean distinctParams;
        private final boolean dualSequence;
        private final boolean optimizeDegraded;

        private Options(
                int sequenceLength,
                int groupCount,
                int candidatesPerGroup,
                int warmupRounds,
                int measurementRounds,
                boolean distinctParams,
                boolean dualSequence,
                boolean optimizeDegraded) {
            this.sequenceLength = positive(sequenceLength, "sequenceLength");
            this.groupCount = positive(groupCount, "groupCount");
            this.candidatesPerGroup = positive(
                    candidatesPerGroup, "candidatesPerGroup");
            this.warmupRounds = nonNegative(warmupRounds, "warmupRounds");
            this.measurementRounds = positive(
                    measurementRounds, "measurementRounds");
            this.distinctParams = distinctParams;
            this.dualSequence = dualSequence;
            this.optimizeDegraded = optimizeDegraded;
        }

        private static Options parse(String[] args) {
            if (args.length > 8) {
                throw new IllegalArgumentException(
                        "Usage: OperatorBatchComparisonDemo "
                                + "[sequenceLength] [groupCount] "
                                + "[candidatesPerGroup] [warmups] [measurements] "
                                + "[distinctParams(0|1)] [dualSequence(0|1)] "
                                + "[optimizeDegraded(0|1)]");
            }
            boolean distinctParams = false;
            if (args.length >= 6) {
                distinctParams = flagArgument(args[5], "distinctParams");
            }
            boolean dualSequence = false;
            if (args.length >= 7) {
                dualSequence = flagArgument(args[6], "dualSequence");
            }
            boolean optimizeDegraded = false;
            if (args.length == 8) {
                optimizeDegraded = flagArgument(args[7], "optimizeDegraded");
            }
            return new Options(
                    argument(args, 0, DEFAULT_SEQUENCE_LENGTH, "sequenceLength"),
                    argument(args, 1, DEFAULT_GROUP_COUNT, "groupCount"),
                    argument(args, 2, DEFAULT_CANDIDATES_PER_GROUP,
                            "candidatesPerGroup"),
                    argument(args, 3, DEFAULT_WARMUP_ROUNDS, "warmupRounds"),
                    argument(args, 4, DEFAULT_MEASUREMENT_ROUNDS,
                            "measurementRounds"),
                    distinctParams,
                    dualSequence,
                    optimizeDegraded);
        }

        private static boolean flagArgument(String value, String name) {
            int flag = Integer.parseInt(value);
            if (flag != 0 && flag != 1) {
                throw new IllegalArgumentException(
                        name + " must be 0 or 1: " + value);
            }
            return flag == 1;
        }

        private static int argument(
                String[] args,
                int index,
                int defaultValue,
                String name) {
            if (index >= args.length) return defaultValue;
            try {
                return Integer.parseInt(args[index]);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(
                        name + " must be an integer: " + args[index], error);
            }
        }

        private static int positive(int value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }

        private static int nonNegative(int value, String name) {
            if (value < 0) {
                throw new IllegalArgumentException(
                        name + " must be non-negative");
            }
            return value;
        }
    }
}
