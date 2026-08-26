#!/usr/bin/env bash
set -uo pipefail

duration="${BENCHMARK_DURATION:-30s}"

restore_kafka() {
  ./loadtest/benchmark/kafka-fault.sh up >/dev/null 2>&1 || true
}
trap restore_kafka EXIT INT TERM

./loadtest/benchmark/kafka-fault.sh down
BENCHMARK_ALLOW_FAILURES=false \
BENCHMARK_TARGET_RPS="${BENCHMARK_TARGET_RPS:-100}" \
K6_DURATION="$duration" \
  ./loadtest/benchmark/run-load.sh normal outbox 1
load_exit=$?
restore_kafka
trap - EXIT INT TERM

./loadtest/benchmark/wait-for-recovery.sh "${RECOVERY_TIMEOUT_SECONDS:-300}" \
  loadtest/k6/results/outbox-kafka-outage-recovery.csv
recovery_exit=$?
./loadtest/benchmark/collect-metrics.sh \
  loadtest/k6/results/outbox-kafka-outage-database-metrics.csv

if [ "$load_exit" -ne 0 ]; then
  exit "$load_exit"
fi
exit "$recovery_exit"
