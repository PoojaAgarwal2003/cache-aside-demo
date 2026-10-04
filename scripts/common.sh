#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

load_environment() {
    if [[ -f "$ROOT/.env" ]]; then
        while IFS= read -r line || [[ -n "$line" ]]; do
            line="${line%$'\r'}"
            [[ -z "$line" || "$line" == \#* ]] && continue
            [[ "$line" =~ ^(APP_PORT|POSTGRES_PORT|REDIS_PORT|DB_PASSWORD)=(.*)$ ]] ||
                { echo 'Unsupported .env entry' >&2; exit 1; }
            name="${BASH_REMATCH[1]}"
            value="${BASH_REMATCH[2]}"
            if [[ "$value" == \"*\" || "$value" == \'*\' ]]; then value="${value:1:-1}"; fi
            if [[ -z "${!name:-}" ]]; then export "$name=$value"; fi
        done < "$ROOT/.env"
    fi
    export APP_PORT="${APP_PORT:-8080}" POSTGRES_PORT="${POSTGRES_PORT:-55432}" REDIS_PORT="${REDIS_PORT:-56379}"
    for value in "$APP_PORT" "$POSTGRES_PORT" "$REDIS_PORT"; do
        [[ "$value" =~ ^[0-9]{4,5}$ ]] && ((10#$value >= 1024 && 10#$value <= 65535)) ||
            { echo 'Ports must be integers in 1024-65535' >&2; exit 1; }
    done
}

compose() {
    command -v docker >/dev/null || { echo 'Install Docker with Linux containers and Compose v2' >&2; exit 1; }
    docker compose --project-name flashsale-lab --project-directory "$ROOT" -f "$ROOT/docker-compose.yml" "$@"
}
