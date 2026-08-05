#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mvn -q -DskipTests package
java -jar target/feature-dag-engine-1.0.0-SNAPSHOT-all.jar
