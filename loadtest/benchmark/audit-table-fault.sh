#!/usr/bin/env bash
set -euo pipefail

action="${1:?Usage: audit-table-fault.sh down|up}"

case "$action" in
  down)
    docker compose exec -T mysql mysql -ufinflow -pfinflow finflow \
      -e "RENAME TABLE transaction_audit_log TO transaction_audit_log_faulted;"
    ;;
  up)
    docker compose exec -T mysql mysql -ufinflow -pfinflow finflow \
      -e "RENAME TABLE transaction_audit_log_faulted TO transaction_audit_log;"
    ;;
  *)
    echo "Usage: audit-table-fault.sh down|up" >&2
    exit 2
    ;;
esac
