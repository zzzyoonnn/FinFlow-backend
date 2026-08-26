#!/usr/bin/env bash
set -euo pipefail

scenario="${1:-normal}"
mode="${2:-sync}"
repetitions="${3:-1}"

for run in $(seq 1 "$repetitions"); do
  run_id="${mode}-${scenario}-$(date +%Y%m%d%H%M%S)-${run}"
  echo "Running benchmark: mode=${mode}, scenario=${scenario}, runId=${run_id}"
  BENCHMARK_SCENARIO="$scenario" \
  BENCHMARK_RUN_ID="$run_id" \
  K6_MODE="$mode" \
  K6_USERNAME="${K6_USERNAME:-benchmark}" \
  K6_PASSWORD="${K6_PASSWORD:-1234}" \
  K6_SCRIPT=transaction-benchmark.js \
    docker compose --profile loadtest run --rm k6
done
