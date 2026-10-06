#!/usr/bin/env bash
set -euo pipefail
strategy="${1:-ATOMIC_SQL}"
scenario=PURCHASE
case "$strategy" in
    COMPARE) scenario=COMPARE; strategy=ATOMIC_SQL ;;
    NONE|ATOMIC_SQL|PESSIMISTIC|OPTIMISTIC|REDIS_ASSISTED) ;;
    *) echo 'Usage: flash-sale.sh [STRATEGY|COMPARE] [export.json]' >&2; exit 2 ;;
esac
(($# <= 2)) || { echo 'For custom parameters use fire-requests.sh --json JSON.' >&2; exit 2; }
arguments=(--json "{\"scenario\":\"$scenario\",\"strategy\":\"$strategy\"}")
if [[ $# == 2 ]]; then arguments+=(--out "$2"); fi
exec bash "$(dirname -- "${BASH_SOURCE[0]}")/fire-requests.sh" "${arguments[@]}"
