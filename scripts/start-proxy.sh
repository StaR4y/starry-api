#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

export SERVER_PORT="${SERVER_PORT:-8080}"
export MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
export MYSQL_PORT="${MYSQL_PORT:-3306}"
export MYSQL_DATABASE="${MYSQL_DATABASE:-kiro_proxy}"
export MYSQL_USERNAME="${MYSQL_USERNAME:-root}"
export MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"
export KIRO_ALLOW_ANONYMOUS_PROXY="${KIRO_ALLOW_ANONYMOUS_PROXY:-false}"
export KIRO_RATE_LIMIT_PER_MINUTE="${KIRO_RATE_LIMIT_PER_MINUTE:-120}"
export KIRO_ADMIN_SESSION_TTL="${KIRO_ADMIN_SESSION_TTL:-12h}"

echo "Starting Starry API on http://127.0.0.1:${SERVER_PORT}"
echo "Database: ${MYSQL_USERNAME}@${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DATABASE}"
echo "API docs: http://127.0.0.1:${SERVER_PORT}/docs"

if [[ "${KIRO_RUN_MODE:-bootRun}" == "jar" ]]; then
  ./gradlew :service:bootJar
  exec java -jar service/build/libs/service-0.1.0-SNAPSHOT.jar
fi

exec ./gradlew :service:bootRun
