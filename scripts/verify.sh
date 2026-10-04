#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
cd "$ROOT"
case "${1:-}" in
    --unit-only)
        [[ $# -eq 1 ]] || { echo 'Usage: scripts/verify.sh [--unit-only]' >&2; exit 1; }
        unset FLASHSALE_TEST_JDBC_URL
        exec ./gradlew test bootJar --console=plain
        ;;
    '')
        unset FLASHSALE_TEST_JDBC_URL
        exec ./gradlew check bootJar --console=plain
        ;;
    *)
        echo 'Usage: scripts/verify.sh [--unit-only]; real Docker is required for acceptance.' >&2
        exit 1
        ;;
esac
