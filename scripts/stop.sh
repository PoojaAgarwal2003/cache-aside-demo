#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
load_environment
echo 'Stop the foreground app with Ctrl+C in its terminal. Stopping only project containers; preserving volumes.'
compose stop
