#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SEQUENCE_LENGTH="${1:-200}"
GROUP_COUNT="${2:-8}"
CANDIDATES_PER_GROUP="${3:-1000}"
WARMUPS="${4:-2}"
MEASUREMENTS="${5:-5}"
DISTINCT_PARAMS="${6:-0}"
DUAL_SEQUENCE="${7:-0}"
OPTIMIZE_DEGRADED="${8:-0}"

mvn -q -DskipTests compile dependency:build-classpath \
  -Dmdep.outputFile=target/demo-classpath.txt

case "$(uname -s 2>/dev/null || true)" in
  MINGW*|MSYS*|CYGWIN*) PATH_SEPARATOR=';' ;;
  *) PATH_SEPARATOR=':' ;;
esac

CLASSPATH="target/classes${PATH_SEPARATOR}$(cat target/demo-classpath.txt)"
java -cp "$CLASSPATH" com.example.featuredag.demo.OperatorBatchComparisonDemo \
  "$SEQUENCE_LENGTH" "$GROUP_COUNT" "$CANDIDATES_PER_GROUP" "$WARMUPS" "$MEASUREMENTS" "$DISTINCT_PARAMS" "$DUAL_SEQUENCE" "$OPTIMIZE_DEGRADED"
