#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
load_environment
profile="${1:-demo}"
case "$profile" in demo|benchmark|default) ;; *) echo 'Profile must be demo, benchmark or default' >&2; exit 1 ;; esac
[[ $# -le 1 ]] || { echo 'Usage: scripts/start.sh [demo|benchmark|default]' >&2; exit 1; }
compose up -d --wait --wait-timeout 90
cd "$ROOT"
./gradlew bootJar --console=plain
build="${FLASHSALE_BUILD_DIR:-$ROOT/build}"
java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
echo "Starting on loopback port $APP_PORT. Ctrl+C stops this foreground app; scripts/stop.sh stops the containers."
exec "$java" -jar "$build/libs/cache-aside-demo.jar" "--spring.profiles.active=$profile"
