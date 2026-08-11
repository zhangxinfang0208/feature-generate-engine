#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

DEMO="${1:-all}"
case "$DEMO" in
  all) DEMO_CLASSES=(
    com.example.featuredag.demo.ScalarOperatorsDemo
    com.example.featuredag.demo.SequenceOperatorsDemo
    com.example.featuredag.demo.OfflineBatchOperatorsDemo
  ) ;;
  scalar) DEMO_CLASSES=(com.example.featuredag.demo.ScalarOperatorsDemo) ;;
  sequence) DEMO_CLASSES=(com.example.featuredag.demo.SequenceOperatorsDemo) ;;
  batch) DEMO_CLASSES=(com.example.featuredag.demo.OfflineBatchOperatorsDemo) ;;
  *)
    echo "Usage: $0 [all|scalar|sequence|batch]" >&2
    exit 2
    ;;
esac

mvn -q -DskipTests compile dependency:build-classpath \
  -Dmdep.outputFile=target/demo-classpath.txt

case "$(uname -s 2>/dev/null || true)" in
  MINGW*|MSYS*|CYGWIN*) PATH_SEPARATOR=';' ;;
  *) PATH_SEPARATOR=':' ;;
esac

CLASSPATH="target/classes${PATH_SEPARATOR}$(cat target/demo-classpath.txt)"
for DEMO_CLASS in "${DEMO_CLASSES[@]}"; do
  java -cp "$CLASSPATH" "$DEMO_CLASS"
done
