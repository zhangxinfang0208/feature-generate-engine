#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

mvn -q -DskipTests test-compile dependency:build-classpath \
  -Dmdep.outputFile=target/test-classpath.txt
case "$(uname -s 2>/dev/null || true)" in
  MINGW*|MSYS*|CYGWIN*) PATH_SEPARATOR=';' ;;
  *) PATH_SEPARATOR=':' ;;
esac
CLASSPATH_VALUE="target/test-classes${PATH_SEPARATOR}target/classes${PATH_SEPARATOR}$(cat target/test-classpath.txt)"
java -ea -cp "$CLASSPATH_VALUE" \
  com.example.featuredag.blackbox.ExternalGoldenCasesVerifier \
  "$ROOT/src/test/resources/external-golden-cases/recommendation-model-feature-set.json" \
  "$ROOT/src/test/resources/external-golden-cases/recommendation-offline-cases.json"
