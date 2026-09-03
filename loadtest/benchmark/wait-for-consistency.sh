#!/usr/bin/env bash
set -euo pipefail

timeout_seconds="${1:-120}"
started_at="$(date +%s)"

mysql_value() {
  docker compose exec -T mysql mysql -N -s -ufinflow -pfinflow finflow -e "$1" 2>/dev/null
}

while true; do
  transactions="$(mysql_value "SELECT COUNT(*) FROM account_transaction;")"
  audits="$(mysql_value "SELECT COUNT(*) FROM transaction_audit_log;")"
  pending="$(mysql_value "SELECT COUNT(*) FROM outbox_event WHERE status='PENDING';")"
  elapsed="$(( $(date +%s) - started_at ))"
  echo "elapsed=${elapsed}s transactions=${transactions} audits=${audits} pending=${pending}"

  if [ "$transactions" = "$audits" ] && [ "$pending" = "0" ]; then
    echo "E2E consistency confirmed in ${elapsed}s"
    exit 0
  fi
  if [ "$elapsed" -ge "$timeout_seconds" ]; then
    echo "E2E consistency was not reached within ${timeout_seconds}s" >&2
    exit 1
  fi
  sleep 1
done
