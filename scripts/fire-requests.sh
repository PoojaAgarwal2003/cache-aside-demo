#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
load_environment
command -v curl >/dev/null && command -v jq >/dev/null ||
    { echo 'Experiment clients require curl and jq (no Node or Python needed).' >&2; exit 1; }
parameters='{"scenario":"READ"}'
output=''
no_wait=false
while (($#)); do
    case "$1" in
        --json) [[ $# -ge 2 ]] || exit 2; parameters="$2"; shift 2 ;;
        --out) [[ $# -ge 2 ]] || exit 2; output="$2"; shift 2 ;;
        --no-wait) no_wait=true; shift ;;
        *) echo 'Usage: fire-requests.sh [--json JSON] [--out FILE] [--no-wait]' >&2; exit 2 ;;
    esac
done
seconds="$(jq -er 'if type == "object" then (.durationSeconds // 60) else error("JSON object required") end
    | select(type == "number" and . == floor and . >= 1 and . <= 120)' <<<"$parameters")"
base="http://127.0.0.1:$APP_PORT"
request() {
    curl --silent --show-error --fail-with-body --noproxy '*' --connect-timeout 2 --max-time 10 \
        -H 'Content-Type: application/json' --request "$1" "$base$2" "${@:3}"
}
status="$(request GET /status)"
jq -e '.application == "FlashSale Lab" and .milestone >= 4' <<<"$status" >/dev/null ||
    { echo 'This port does not serve milestone-4 FlashSale Lab.' >&2; exit 1; }
run=''
finished=false
cleanup() {
    if [[ -n "$run" && "$finished" != true ]]; then
        request POST "/demo/runs/$run/cancel" >/dev/null ||
            echo "Cancellation unconfirmed for $run; inspect the persisted run." >&2
    fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM
started="$(request POST /demo/runs --data-raw "$parameters")"
run="$(jq -er '.runId' <<<"$started")"
[[ "$run" =~ ^[a-f0-9-]{36}$ ]] || { echo 'Invalid run identifier from server.' >&2; exit 1; }
echo "Run $run; inspect /demo/runs/$run or POST /demo/runs/$run/cancel." >&2
if [[ "$no_wait" == true ]]; then finished=true; printf '%s\n' "$started"; exit 0; fi
deadline=$((SECONDS + seconds + 90))
while :; do
    snapshot="$(request GET "/demo/runs/$run")"
    if jq -e '.active == false' <<<"$snapshot" >/dev/null; then finished=true; break; fi
    ((SECONDS < deadline)) || { echo "Run $run exceeded dispatch/drain allowance." >&2; exit 1; }
    sleep 0.5
done
exported="$(request GET "/demo/runs/$run/export")"
if [[ -n "$output" ]]; then printf '%s\n' "$exported" >"$output"; else printf '%s\n' "$exported"; fi
jq -r '"State: \(.state); inventory: \(.result.invariantVerdict)."' <<<"$snapshot" >&2
jq -e '.state != "FAILED" and .state != "INCONCLUSIVE" and .state != "INTERRUPTED"' <<<"$snapshot" >/dev/null
