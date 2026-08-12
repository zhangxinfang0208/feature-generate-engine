#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SEQUENCE_LENGTH="${1:-200}"
GROUP_COUNT="${2:-8}"
CANDIDATES_PER_GROUP="${3:-1000}"
WARMUPS="${4:-2}"
MEASUREMENTS="${5:-5}"

mvn -q -DskipTests compile dependency:build-classpath \
  -Dmdep.outputFile=target/demo-classpath.txt

case "$(uname -s 2>/dev/null || true)" in
  MINGW*|MSYS*|CYGWIN*) PATH_SEPARATOR=';' ;;
  *) PATH_SEPARATOR=':' ;;
esac

CLASSPATH="target/classes${PATH_SEPARATOR}$(cat target/demo-classpath.txt)"
java -cp "$CLASSPATH" com.example.featuredag.demo.FeatureExpressionBatchComparisonDemo \
  "$SEQUENCE_LENGTH" "$GROUP_COUNT" "$CANDIDATES_PER_GROUP" "$WARMUPS" "$MEASUREMENTS"
