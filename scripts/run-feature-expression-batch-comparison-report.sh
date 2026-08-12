#!/usr/bin/env bash
set -euo pipefail

# 特征表达式层 Batch vs Individual 验证报告：按多个序列长度、多次重复运行
# FeatureExpressionBatchComparisonDemo，聚合 median-of-medians，输出 markdown。
#
# 用法:
#   run-feature-expression-batch-comparison-report.sh \
#     [序列长度列表] [重复次数] [组数] [每组候选数] [预热轮] [测量轮]

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SEQUENCE_LENGTHS="${1:-50 200 1000 3000}"
REPEATS="${2:-3}"
GROUP_COUNT="${3:-8}"
CANDIDATES_PER_GROUP="${4:-1000}"
WARMUPS="${5:-2}"
MEASUREMENTS="${6:-5}"

mvn -q -DskipTests compile dependency:build-classpath \
  -Dmdep.outputFile=target/demo-classpath.txt

case "$(uname -s 2>/dev/null || true)" in
  MINGW*|MSYS*|CYGWIN*) PATH_SEPARATOR=';' ;;
  *) PATH_SEPARATOR=':' ;;
esac

CLASSPATH="target/classes${PATH_SEPARATOR}$(cat target/demo-classpath.txt)"
REPORT_DIR="target/feature-expression-batch-report"
rm -rf "$REPORT_DIR"
mkdir -p "$REPORT_DIR"

for SEQUENCE_LENGTH in $SEQUENCE_LENGTHS; do
    for REP in $(seq 1 "$REPEATS"); do
        java -cp "$CLASSPATH" com.example.featuredag.demo.FeatureExpressionBatchComparisonDemo \
            "$SEQUENCE_LENGTH" "$GROUP_COUNT" "$CANDIDATES_PER_GROUP" \
            "$WARMUPS" "$MEASUREMENTS" \
            > "$REPORT_DIR/length-$SEQUENCE_LENGTH-rep-$REP.txt"
    done
done

echo "# 特征表达式层 Batch vs Individual 验证报告"
echo
echo "## 验证环境与方法"
echo
echo "- 日期：$(date +%Y-%m-%d)"
echo "- 机器：$(uname -s -r -m 2>/dev/null || true)"
echo "- Java：$(java -version 2>&1 | head -1)"
echo "- 命令：$0 序列长度=[$SEQUENCE_LENGTHS] 重复=$REPEATS"
echo "    组数=$GROUP_COUNT 每组候选数=$CANDIDATES_PER_GROUP 预热轮=$WARMUPS 测量轮=$MEASUREMENTS"
echo
echo "8 个特征表达式（首期 8 个算子各一，走完整引擎链路：表达式解析 → 逻辑 DAG →"
echo "物理计划 → runtime 分派）："
echo "- 候选特征（USER×ITEM）：bucket_level=discrete(amount, [0,10,50,100,500])、"
echo "  log_amount=log_base(amount, 2, 1048576)、"
echo "  target_positions=find_indices(codes, target_tag)、"
echo "  delta_sequence=calc_delta_seq(numbers, delta_base)；"
echo "- 共享序列特征（USER）：code_window=slice_by_indices(codes, [0..10])、"
echo "  behavior_length=get_seq_length(codes)、distinct_codes=count_distinct(codes)、"
echo "  joined_window=zip_concat(slice_by_indices(codes, [0,2,4]), slice_by_indices(numbers, [0,2,4]), {\"delimiter\":\"|\"})。"
echo
echo "两条执行路径：individual（每请求组一次 generate，共 8 次请求）与"
echo "grouped（一次 generateBatch 聚合全部请求组）。引擎按输入载体分派（C10）："
echo "候选特征恒走原生 Batch kernel；纯共享序列特征在 individual 下走 Single kernel，"
echo "在 grouped 下随共享值向量化走请求批域。"
echo
echo "统计口径：单次运行内 5 个测量轮次取 median；每个配置重复 $REPEATS 次，"
echo "再取 median-of-medians。"
echo
echo "| 序列长度 | individual median (ms) | grouped median (ms) | grouped/individual |"
echo "|---|---|---|---|"
for SEQUENCE_LENGTH in $SEQUENCE_LENGTHS; do
    local_files=()
    for REP in $(seq 1 "$REPEATS"); do
        local_files+=("$REPORT_DIR/length-$SEQUENCE_LENGTH-rep-$REP.txt")
    done
    LC_ALL=C gawk -v length_name="$SEQUENCE_LENGTH" '
        function median(arr, n,   sorted, i, m) {
            for (i = 1; i <= n; i++) sorted[i] = arr[i];
            asort(sorted);
            m = int((n + 1) / 2);
            if (n % 2 == 1) return sorted[m];
            return (sorted[m] + sorted[m + 1]) / 2.0;
        }
        /^individual/ { ind[++ci] = $0; match($0, /medianMs=[0-9.]+/); indv[++ci2] = substr($0, RSTART + 9, RLENGTH - 9) + 0 }
        /^grouped/ { grp[++cg] = $0; match($0, /medianMs=[0-9.]+/); grpv[++cg2] = substr($0, RSTART + 9, RLENGTH - 9) + 0 }
        END {
            mi = median(indv, ci2);
            mg = median(grpv, cg2);
            printf "| %s | %.3f | %.3f | %.2fx |\n", length_name, mi, mg, mi / mg;
        }' "${local_files[@]}"
done
