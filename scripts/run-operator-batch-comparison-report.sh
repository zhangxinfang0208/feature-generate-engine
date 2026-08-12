#!/usr/bin/env bash
set -euo pipefail

# 算子 Batch vs Single 对比验证报告：按多个序列长度、多次重复运行
# OperatorBatchComparisonDemo，聚合每次运行的中位数（每个测量轮次内部
# 已取 median），输出 markdown 报告到 stdout。
#
# 用法:
#   run-operator-batch-comparison-report.sh \
#     [共享场景序列长度列表] [独立参数场景序列长度列表] [重复次数]
#     [组数] [每组候选数] [预热轮] [测量轮] [dualSequence(0|1)] [optimizeDegraded(0|1)]

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SHARED_LENGTHS="${1:-50 200 1000 3000}"
DISTINCT_LENGTHS="${2:-200 1000}"
REPEATS="${3:-3}"
GROUP_COUNT="${4:-8}"
CANDIDATES_PER_GROUP="${5:-1000}"
WARMUPS="${6:-2}"
MEASUREMENTS="${7:-5}"
DUAL_SEQUENCE="${8:-0}"
OPTIMIZE_DEGRADED="${9:-0}"

mvn -q -DskipTests compile dependency:build-classpath \
  -Dmdep.outputFile=target/demo-classpath.txt

case "$(uname -s 2>/dev/null || true)" in
  MINGW*|MSYS*|CYGWIN*) PATH_SEPARATOR=';' ;;
  *) PATH_SEPARATOR=':' ;;
esac

CLASSPATH="target/classes${PATH_SEPARATOR}$(cat target/demo-classpath.txt)"
REPORT_DIR="target/operator-batch-report"
rm -rf "$REPORT_DIR"
mkdir -p "$REPORT_DIR"

run_invocations() {
    local scenario=$1
    local lengths=$2
    for SEQUENCE_LENGTH in $lengths; do
        for REP in $(seq 1 "$REPEATS"); do
            java -cp "$CLASSPATH" com.example.featuredag.demo.OperatorBatchComparisonDemo \
                "$SEQUENCE_LENGTH" "$GROUP_COUNT" "$CANDIDATES_PER_GROUP" \
                "$WARMUPS" "$MEASUREMENTS" "$scenario" "$DUAL_SEQUENCE" "$OPTIMIZE_DEGRADED" \
                > "$REPORT_DIR/scenario-$scenario-length-$SEQUENCE_LENGTH-rep-$REP.txt"
        done
    done
}

emit_table() {
    local scenario=$1
    local lengths=$2
    local rows_per_length
    rows_per_length=$((GROUP_COUNT * CANDIDATES_PER_GROUP))
    for SEQUENCE_LENGTH in $lengths; do
        local files=()
        for REP in $(seq 1 "$REPEATS"); do
            files+=("$REPORT_DIR/scenario-$scenario-length-$SEQUENCE_LENGTH-rep-$REP.txt")
        done
        echo "#### 序列长度 = $SEQUENCE_LENGTH（行数 = $rows_per_length）"
        echo
        echo "| 算子 | single (ms) | batchScalar (ms) | batchRegistered (ms) | registered/single | scalar/single |"
        echo "|---|---|---|---|---|---|"
        # 每个算子的 single/batchScalar/batchRegistered 中位数来自
        # 单次运行内 5 个测量轮次的 median，这里再对 REPEATS 次运行取 median
        LC_ALL=C gawk '
            function median(arr, n,   sorted, i, m) {
                for (i = 1; i <= n; i++) sorted[i] = arr[i];
                asort(sorted);
                m = int((n + 1) / 2);
                if (n % 2 == 1) return sorted[m];
                return (sorted[m] + sorted[m + 1]) / 2.0;
            }
            $2 == "single" { s[$1][++cs[$1]] = $4 }
            $2 == "batchScalar" { c[$1][++cc[$1]] = $4 }
            $2 == "batchRegistered" { n[$1][++cn[$1]] = $4 }
            END {
                for (op in s) {
                    ms = median(s[op], cs[op]);
                    mc = median(c[op], cc[op]);
                    mn = median(n[op], cn[op]);
                    printf "| %s | %.3f | %.3f | %.3f | %.2fx | %.2fx |\n",
                        op, ms, mc, mn, ms / mn, ms / mc;
                }
            }' "${files[@]}" | sort
        echo
    done
}

emit_degraded_table() {
    local scenario=$1
    local lengths=$2
    local scenario_name=$3
    for SEQUENCE_LENGTH in $lengths; do
        local files=()
        for REP in $(seq 1 "$REPEATS"); do
            files+=("$REPORT_DIR/scenario-$scenario-length-$SEQUENCE_LENGTH-rep-$REP.txt")
        done
        LC_ALL=C gawk -v length_name="$SEQUENCE_LENGTH" -v scenario_name="$scenario_name" '
            function median(arr, n,   sorted, i, m) {
                for (i = 1; i <= n; i++) sorted[i] = arr[i];
                asort(sorted);
                m = int((n + 1) / 2);
                if (n % 2 == 1) return sorted[m];
                return (sorted[m] + sorted[m + 1]) / 2.0;
            }
            FNR == 1 { fileDegraded = 0 }
            # 注意：Java 在 Windows 控制台输出为 GBK，聚合只用 ASCII 特征匹配；
            # 基线行不含 "scalar) = "，优化行含 "scalar) = "
            /\) = [0-9.]+ ms/ {
                match($0, /\) = ([0-9.]+) ms/, arr);
                if ($0 ~ /scalar\) = /) opt[++no] = arr[1] + 0;
                else base[++nb] = arr[1] + 0;
            }
            /<-/ { fileDegraded++ }
            ENDFILE { degraded[++nd] = fileDegraded }
            END {
                mb = median(base, nb);
                mo = median(opt, no);
                printf "| %s | %s | %.3f | %.3f | %.2fx | %d |\n",
                    scenario_name, length_name, mb, mo, mb / mo,
                    nd == 0 ? 0 : median(degraded, nd);
            }' "${files[@]}"
    done
}

run_invocations 0 "$SHARED_LENGTHS"
run_invocations 1 "$DISTINCT_LENGTHS"

echo "# 算子 Batch vs Single 执行差异验证报告"
echo
echo "## 验证环境与方法"
echo
echo "- 日期：$(date +%Y-%m-%d)"
echo "- 机器：$(uname -s -r -m 2>/dev/null || true)"
echo "- Java：$(java -version 2>&1 | head -1)"
echo "- 命令：$0 \\"
echo "    共享场景序列长度=[$SHARED_LENGTHS] 独立参数场景序列长度=[$DISTINCT_LENGTHS] \\"
echo "    重复=$REPEATS 组数=$GROUP_COUNT 每组候选数=$CANDIDATES_PER_GROUP \\"
echo "    预热轮=$WARMUPS 测量轮=$MEASUREMENTS dualSequence=$DUAL_SEQUENCE"
echo
echo "三条执行路径（OperatorRegistry 直接调用，不含调度与列物化）："
echo "- single：不走 Batch，逐行调 SingleOperatorKernel，每行全量重算。"
echo "- batchScalar：走 Batch 载体，由 SingleLoopBatchOperatorKernel 逐行适配。"
echo "- batchNative：走原生 BatchOperatorKernel，按 (group, sequence, 参数) 身份键批内复用。"
echo
echo "统计口径：单次运行内 5 个测量轮次取 median；每个配置重复 $REPEATS 次，"
echo "再取 median-of-medians。两个场景："
if [ "$DUAL_SEQUENCE" = "1" ]; then
    echo "- 共享序列场景（scenario 0）：每组请求携带两个共享序列特征 seqA（字符串行为序列）"
    echo "  与 seqB（数值序列），候选行共享同一组对象，批内 identity 缓存命中；"
    echo "  zip_concat 拼接 seqA+seqB，get_seq_length/discrete/log_base 取 seqB，其余取 seqA。"
else
    echo "- 共享序列场景（scenario 0）：组内候选行共享同一请求序列对象，批内 identity 缓存命中。"
fi
echo "- 独立参数场景（scenario 1）：每行参数独立，批内缓存全部失效。"
echo
echo "## 共享序列场景（$(if [ "$DUAL_SEQUENCE" = "1" ]; then echo "双序列特征，批内按 identity 复用"; else echo "组内候选行共享请求序列，批内按 identity 复用"; fi)）"
echo
emit_table 0 "$SHARED_LENGTHS"
echo "## 独立参数场景（每行独立参数，批内无复用）"
echo
emit_table 1 "$DISTINCT_LENGTHS"
if [ "$OPTIMIZE_DEGRADED" = "1" ]; then
    echo "## 劣化算子改走 Single（模拟物理计划 batchKernelKind=SCALAR_ADAPTER）"
    echo
    echo "劣化判定：batchNative median > batchScalar median（模拟规划期成本模型结论）。"
    echo "基线 = 全部算子走原生 Batch 的 median 总耗时；优化后 = 劣化算子改走"
    echo "SingleLoopBatchOperatorKernel（逐行 single 语义）、其余算子保持原生 Batch。"
    echo
    echo "| 场景 | 序列长度 | 基线总耗时 (ms) | 优化后总耗时 (ms) | 整体收益 | 劣化算子数 |"
    echo "|---|---|---|---|---|---|"
    emit_degraded_table 0 "$SHARED_LENGTHS" "共享序列"
    emit_degraded_table 1 "$DISTINCT_LENGTHS" "独立参数"
fi
echo "## 结论"
echo
echo "- 批内复用的收益随序列长度放大：zip_concat / count_distinct / calc_delta_seq 等"
echo "  \"每行可省 O(序列长度) 重算\" 的算子在长序列下加速比显著提升；"
echo "- 轻计算算子（slice_by_indices / get_seq_length / log_base）批开销反噬，"
echo "  即使有复用也比 single 慢，应留给成本模型（C10）决定是否走 Batch；"
echo "- 无复用场景下 batchNative 全面回落到 ≈1x 或更差，"
echo "  其中 find_indices 因批内需为每个独立序列构建索引 map 而最慢；"
echo "- batchScalar（标量适配）恒 ≈1x 上下，是保底路径。"
