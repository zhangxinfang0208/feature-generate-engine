#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if ! command -v java >/dev/null 2>&1; then
  echo "Java is required (JDK 21 or newer)." >&2
  exit 1
fi
if ! command -v mvn >/dev/null 2>&1; then
  echo "Maven is required." >&2
  exit 1
fi

JAVA_SPEC="$(java -XshowSettings:properties -version 2>&1 \
  | awk -F= '/java.specification.version/ {gsub(/[[:space:]]/, "", $2); print $2; exit}')"
JAVA_MAJOR="${JAVA_SPEC#1.}"
JAVA_MAJOR="${JAVA_MAJOR%%.*}"
if [[ ! "$JAVA_MAJOR" =~ ^[0-9]+$ ]] || (( JAVA_MAJOR < 21 )); then
  echo "JDK 21 or newer is required; detected specification version: ${JAVA_SPEC:-unknown}." >&2
  exit 1
fi

case "$(uname -s 2>/dev/null || true)" in
  MINGW*|MSYS*|CYGWIN*) PATH_SEPARATOR=';' ;;
  *) PATH_SEPARATOR=':' ;;
esac

mvn -q -DskipTests test-compile dependency:build-classpath \
  -Dmdep.outputFile=target/test-classpath.txt
CP="target/test-classes${PATH_SEPARATOR}target/classes${PATH_SEPARATOR}$(cat target/test-classpath.txt)"
java -ea -cp "$CP" com.example.featuredag.DagEngineSelfTest
# 新增 UT（JUnit 4 独立文件）由 surefire 执行；失败时 mvn 非零退出，脚本随之失败。
mvn -q test
