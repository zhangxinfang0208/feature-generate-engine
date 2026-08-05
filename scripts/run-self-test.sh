#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mvn -q -DskipTests test-compile dependency:build-classpath \
  -Dmdep.outputFile=target/test-classpath.txt
CP="target/test-classes:target/classes:$(cat target/test-classpath.txt)"
java -ea -cp "$CP" com.example.featuredag.DagEngineSelfTest
