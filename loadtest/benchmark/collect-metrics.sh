#!/usr/bin/env bash
set -euo pipefail

result_file="${1:-loadtest/k6/results/database-metrics.csv}"

mysql_value() {
  docker compose exec -T mysql mysql -N -s -ufinflow -pfinflow finflow -e "$1" 2>/dev/null
}

table_exists() {
  mysql_value "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='finflow' AND table_name='$1';"
}

count_table() {
  if [ "$(table_exists "$1")" = "1" ]; then
    mysql_value "SELECT COUNT(*) FROM $1;"
  else
    echo "NA"
  fi
}

transactions="$(count_table account_transaction)"
audits="$(count_table transaction_audit_log)"
pending="NA"
published="NA"
processed="$(count_table processed_event)"
missing="NA"
duplicates="NA"
completion_rate="NA"
data_loss_rate="NA"

if [ "$(table_exists transaction_audit_log)" = "1" ]; then
  missing="$(mysql_value "SELECT COUNT(*) FROM account_transaction t LEFT JOIN transaction_audit_log a ON a.transaction_id=t.id WHERE a.id IS NULL;")"
  duplicates="$(mysql_value "SELECT COALESCE(SUM(c-1),0) FROM (SELECT COUNT(*) c FROM transaction_audit_log GROUP BY transaction_id HAVING COUNT(*) > 1) d;")"
fi
if [ "$transactions" != "NA" ] && [ "$audits" != "NA" ]; then
  completion_rate="$(awk -v a="$audits" -v t="$transactions" 'BEGIN { if (t == 0) print "100.000000"; else printf "%.6f", (a/t)*100 }')"
  data_loss_rate="$(awk -v m="$missing" -v t="$transactions" 'BEGIN { if (t == 0) print "0.000000"; else printf "%.6f", (m/t)*100 }')"
fi
if [ "$(table_exists outbox_event)" = "1" ]; then
  pending="$(mysql_value "SELECT COUNT(*) FROM outbox_event WHERE status='PENDING';")"
  published="$(mysql_value "SELECT COUNT(*) FROM outbox_event WHERE status='PUBLISHED';")"
fi

if [ ! -f "$result_file" ]; then
  echo "timestamp,transactions,audits,audit_completion_rate_pct,data_loss_rate_pct,missing_audits,duplicate_audits,outbox_pending,outbox_published,processed_events" > "$result_file"
fi
echo "$(date -Iseconds),$transactions,$audits,$completion_rate,$data_loss_rate,$missing,$duplicates,$pending,$published,$processed" | tee -a "$result_file"
