#!/usr/bin/env bash
set -euo pipefail

action="${1:?Usage: kafka-fault.sh down|up}"

case "$action" in
  down) docker compose stop kafka ;;
  up) docker compose start kafka ;;
  *)
    echo "Usage: kafka-fault.sh down|up" >&2
    exit 2
    ;;
esac
