#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ARGS="${*:-com.example.featuredag.benchmark.FeatureDagEngineBenchmark -prof gc -rf json -rff target/jmh-result.json}"
mvn -q -Pbenchmarks test-compile exec:java \
  -Dexec.mainClass=org.openjdk.jmh.Main \
  -Dexec.classpathScope=test \
  -Dexec.args="$ARGS"
