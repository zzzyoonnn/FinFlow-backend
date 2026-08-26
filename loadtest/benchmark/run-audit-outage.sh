#!/usr/bin/env bash
set -uo pipefail

mode="${1:-sync}"
duration="${BENCHMARK_DURATION:-30s}"

restore_audit_table() {
  ./loadtest/benchmark/audit-table-fault.sh up >/dev/null 2>&1 || true
}
trap restore_audit_table EXIT INT TERM

./loadtest/benchmark/audit-table-fault.sh down
BENCHMARK_ALLOW_FAILURES=true \
BENCHMARK_TARGET_RPS="${BENCHMARK_TARGET_RPS:-100}" \
K6_DURATION="$duration" \
  ./loadtest/benchmark/run-load.sh normal "$mode" 1
load_exit=$?
restore_audit_table
trap - EXIT INT TERM

./loadtest/benchmark/collect-metrics.sh \
  "loadtest/k6/results/${mode}-audit-outage-database-metrics.csv"
exit "$load_exit"
