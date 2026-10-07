#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
load_environment
command -v node >/dev/null || { echo 'Benchmark development tooling requires Node 22+' >&2; exit 1; }
exec node "$ROOT/scripts/benchmark.mjs" "$@"
