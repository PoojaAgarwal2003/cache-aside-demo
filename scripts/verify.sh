#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
cd "$ROOT"
case "${1:-}" in
    --unit-only)
        [[ $# -eq 1 ]] || { echo 'Usage: scripts/verify.sh [--unit-only|--browser-only]' >&2; exit 1; }
        unset FLASHSALE_TEST_JDBC_URL FLASHSALE_TEST_REDIS_WSL FLASHSALE_TEST_REDIS_BINARY
        exec ./gradlew test bootJar --console=plain
        ;;
    --browser-only)
        [[ $# -eq 1 ]] || { echo 'Usage: scripts/verify.sh [--unit-only|--browser-only]' >&2; exit 1; }
        command -v npm >/dev/null || { echo 'Browser verification needs Node 22+, npm ci and Playwright Chromium.' >&2; exit 1; }
        unset FLASHSALE_TEST_JDBC_URL FLASHSALE_TEST_REDIS_WSL FLASHSALE_TEST_REDIS_BINARY
        npm test
        exec ./gradlew browserTest --console=plain
        ;;
    '')
        unset FLASHSALE_TEST_JDBC_URL FLASHSALE_TEST_REDIS_WSL FLASHSALE_TEST_REDIS_BINARY
        exec ./gradlew check bootJar --console=plain
        ;;
    *)
        echo 'Usage: scripts/verify.sh [--unit-only|--browser-only]; real Docker is required for acceptance.' >&2
        exit 1
        ;;
esac
