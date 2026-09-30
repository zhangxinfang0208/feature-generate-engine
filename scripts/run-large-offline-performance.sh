#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# 每个配置/重复次数单独启动 JVM。可用 JAVA_TOOL_OPTIONS 设置生产堆、GC、CPU 参数。
WARMUP_SECONDS=${WARMUP_SECONDS:-15}
MEASURE_SECONDS=${MEASURE_SECONDS:-30}
SEQUENCE_LENGTH=${SEQUENCE_LENGTH:-384}
BATCH_SIZES=${BATCH_SIZES:-"1 32 128 512 1000"}
POOL_SIZE=${POOL_SIZE:-2}
RUNS=${RUNS:-3}
SCOPES=${SCOPES:-"engine request"}
PROFILE=${PROFILE:-false}
INPUT_MODE=${INPUT_MODE:-borrowed}
OUTPUT_DIRECTORY=${OUTPUT_DIRECTORY:-target/large-offline-performance}
mvn -q -DskipTests test-compile dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt
CP="target/test-classes:target/classes:$(cat target/test-classpath.txt)"
for ((run=1; run<=RUNS; run++)); do
    for scope in $SCOPES; do
        for mode in single batch; do
            for size in $BATCH_SIZES; do
                # 相同批大小与输入池：single 逐行执行整批，batch 一次执行整批。
                out="$OUTPUT_DIRECTORY/$mode-$scope-b$size-run$run"
                java -XX:FlightRecorderOptions=stackdepth=128 -cp "$CP" \
                    com.example.featuredag.performance.LargeOfflineBenchmark \
                    "$out" "$WARMUP_SECONDS" "$MEASURE_SECONDS" "$SEQUENCE_LENGTH" "$size" "$POOL_SIZE" "$mode" "$scope" "$PROFILE" "$INPUT_MODE"
                if [[ "$PROFILE" == true ]]; then
                    java -cp "$CP" com.example.featuredag.performance.JfrFlamegraphExporter "$out/measurement.jfr" "$out"
                    python3 scripts/render-jfr-flames.py "$out"
                fi
            done
        done
    done
done
