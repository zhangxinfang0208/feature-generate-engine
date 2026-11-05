#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${1:-target/columnar-profile}"
# 交付录制只保留诊断事件，不导出环境变量、系统属性等无关进程信息。
EVENTS='featuredag.Request,jdk.GarbageCollection,jdk.GCPhasePause,jdk.GCPhaseConcurrent,jdk.ZAllocationStall,jdk.Compilation,jdk.CompilerPhase,jdk.CompilationFailure,jdk.Deoptimization,jdk.ExecutionSample,jdk.NativeMethodSample,jdk.CPULoad,jdk.ThreadCPULoad,jdk.ObjectAllocationSample,jdk.SafepointBegin,jdk.SafepointEnd'
mkdir -p "$OUT/diagnostics"
for mode in row-borrowed column-borrowed column-result; do
  jfr scrub --include-events "$EVENTS" "$OUT/profile-$mode.jfr" "$OUT/diagnostics/$mode.jfr"
  jfr print --json --events "featuredag.Request,jdk.GarbageCollection,jdk.GCPhasePause,jdk.ZAllocationStall,jdk.Compilation,jdk.CompilationFailure,jdk.Deoptimization,jdk.ExecutionSample,jdk.CPULoad" --stack-depth 64 "$OUT/diagnostics/$mode.jfr" > "$OUT/diagnostics/$mode.json"
  python3 scripts/analyze-columnar-jfr.py "$OUT/diagnostics/$mode.json"
done
