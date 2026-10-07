#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
command -v node >/dev/null || { echo 'Walkthrough development tooling requires Node 22+' >&2; exit 1; }
command -v docker >/dev/null || { echo 'Real Docker Linux containers and Compose v2 are required' >&2; exit 1; }
cd "$ROOT"
./gradlew bootJar --console=plain
exec node "$ROOT/scripts/walkthrough.mjs" "$@"
