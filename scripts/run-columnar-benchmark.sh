#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -q -DskipTests test-compile dependency:build-classpath -Dmdep.outputFile=target/benchmark-classpath.txt
BENCH_CP="target/test-classes:target/classes:$(cat target/benchmark-classpath.txt)"
mkdir -p target/columnar-baseline
for round in 1 2 3; do
  case "$round" in
    1) modes=(row-snapshot row-borrowed column-borrowed);;
    2) modes=(column-borrowed row-snapshot row-borrowed);;
    3) modes=(row-borrowed column-borrowed row-snapshot);;
  esac
  for mode in "${modes[@]}"; do
    java -Xms2g -Xmx2g -XX:+UseZGC -XX:ActiveProcessorCount=4 \
      -cp "$BENCH_CP" com.example.featuredag.performance.ColumnarOnlineBenchmark \
      "$mode" 15 20 "target/columnar-baseline/${round}-${mode}.json"
  done
done
