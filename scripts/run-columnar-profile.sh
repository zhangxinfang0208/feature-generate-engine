#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -q -DskipTests test-compile dependency:build-classpath -Dmdep.outputFile=target/benchmark-classpath.txt
BENCH_CP="target/test-classes:target/classes:$(cat target/benchmark-classpath.txt)"
OUT="${1:-target/columnar-profile}"
mkdir -p "$OUT"
# 普通性能轮次不启用 JFR；诊断轮次另外运行，不混合汇总。
for round in 1 2 3 profile; do
  case "$round" in
    1) modes=(row-borrowed column-borrowed column-result);;
    2) modes=(column-result row-borrowed column-borrowed);;
    3) modes=(column-borrowed column-result row-borrowed);;
    profile) modes=(row-borrowed column-borrowed column-result);;
  esac
  for mode in "${modes[@]}"; do
    extra=()
    if [[ "$round" == profile ]]; then extra=("$OUT/$round-$mode.jfr"); fi
    java -Xms2g -Xmx2g -XX:+UseZGC -XX:ActiveProcessorCount=4 \
      "-Xlog:gc*,safepoint:file=$OUT/$round-$mode-gc.log:uptime,level,tags" \
      -cp "$BENCH_CP" com.example.featuredag.performance.ColumnarOnlineBenchmark \
      "$mode" 20 30 "$OUT/$round-$mode.json" "${extra[@]}"
  done
done
