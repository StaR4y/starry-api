#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

export SERVER_PORT="${SERVER_PORT:-8080}"
export SERVER_ADDRESS="${SERVER_ADDRESS:-127.0.0.1}"
export POSTGRES_HOST="${POSTGRES_HOST:-localhost}"
export POSTGRES_PORT="${POSTGRES_PORT:-5432}"
export POSTGRES_DATABASE="${POSTGRES_DATABASE:-starry_api}"
export POSTGRES_USERNAME="${POSTGRES_USERNAME:-starry_api}"
export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-}"
export POSTGRES_MIGRATION_USERNAME="${POSTGRES_MIGRATION_USERNAME:-starry_api_migration}"
export POSTGRES_MIGRATION_PASSWORD="${POSTGRES_MIGRATION_PASSWORD:-}"
export POSTGRES_SSL_MODE="${POSTGRES_SSL_MODE:-disable}"
export STARRY_ALLOW_ANONYMOUS_PROXY="${STARRY_ALLOW_ANONYMOUS_PROXY:-${KIRO_ALLOW_ANONYMOUS_PROXY:-false}}"
export STARRY_RATE_LIMIT_PER_MINUTE="${STARRY_RATE_LIMIT_PER_MINUTE:-${KIRO_RATE_LIMIT_PER_MINUTE:-120}}"
export STARRY_ADMIN_SESSION_TTL="${STARRY_ADMIN_SESSION_TTL:-${KIRO_ADMIN_SESSION_TTL:-12h}}"

echo "Starting Starry API on http://127.0.0.1:${SERVER_PORT}"
echo "Database: ${POSTGRES_USERNAME}@${POSTGRES_HOST}:${POSTGRES_PORT}/${POSTGRES_DATABASE}"
echo "API docs: http://127.0.0.1:${SERVER_PORT}/docs"

if [[ "${STARRY_RUN_MODE:-${KIRO_RUN_MODE:-bootRun}}" == "jar" ]]; then
  ./gradlew :service:bootJar
  exec java -jar service/build/libs/service-0.1.0-SNAPSHOT.jar
fi

exec ./gradlew :service:bootRun
