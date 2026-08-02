#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if ! command -v docker >/dev/null 2>&1; then
  echo "docker is required. Install Docker from 1Panel first." >&2
  exit 1
fi

if docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE=(docker-compose)
else
  echo "docker compose is required. Install Docker Compose from 1Panel first." >&2
  exit 1
fi

if [[ ! -f .env ]]; then
  cp .env.example .env
  echo ".env was created from .env.example."
  echo "Edit .env and replace all PostgreSQL passwords, then run this script again."
  exit 1
fi

env_value() {
  local name="$1"
  grep -E "^${name}=" .env | tail -n 1 | cut -d= -f2- || true
}

POSTGRES_BOOTSTRAP_PASSWORD_VALUE="$(env_value POSTGRES_BOOTSTRAP_PASSWORD)"
POSTGRES_MIGRATION_PASSWORD_VALUE="$(env_value POSTGRES_MIGRATION_PASSWORD)"
POSTGRES_PASSWORD_VALUE="$(env_value POSTGRES_PASSWORD)"

if [[ -z "$POSTGRES_BOOTSTRAP_PASSWORD_VALUE" \
  || -z "$POSTGRES_MIGRATION_PASSWORD_VALUE" \
  || -z "$POSTGRES_PASSWORD_VALUE" ]]; then
  echo "Refusing to deploy with missing or empty PostgreSQL passwords in .env." >&2
  exit 1
fi

if [[ "$POSTGRES_BOOTSTRAP_PASSWORD_VALUE" == change-me-* \
  || "$POSTGRES_MIGRATION_PASSWORD_VALUE" == change-me-* \
  || "$POSTGRES_PASSWORD_VALUE" == change-me-* ]]; then
  echo "Refusing to deploy with default database passwords in .env." >&2
  echo "Edit .env and set strong, distinct bootstrap, migration, and application passwords." >&2
  exit 1
fi

if [[ "$POSTGRES_BOOTSTRAP_PASSWORD_VALUE" == "$POSTGRES_MIGRATION_PASSWORD_VALUE" \
  || "$POSTGRES_BOOTSTRAP_PASSWORD_VALUE" == "$POSTGRES_PASSWORD_VALUE" \
  || "$POSTGRES_MIGRATION_PASSWORD_VALUE" == "$POSTGRES_PASSWORD_VALUE" ]]; then
  echo "Refusing to deploy with reused PostgreSQL passwords in .env." >&2
  exit 1
fi

HOST_HTTP_PORT_VALUE="$(grep -E '^HOST_HTTP_PORT=' .env | tail -n 1 | cut -d= -f2- || true)"
HOST_HTTP_PORT_VALUE="${HOST_HTTP_PORT_VALUE:-18080}"
HOST_BIND_ADDR_VALUE="$(grep -E '^HOST_BIND_ADDR=' .env | tail -n 1 | cut -d= -f2- || true)"
HOST_BIND_ADDR_VALUE="${HOST_BIND_ADDR_VALUE:-0.0.0.0}"
export HOST_BIND_ADDR="$HOST_BIND_ADDR_VALUE"
export HOST_HTTP_PORT="$HOST_HTTP_PORT_VALUE"
HOST_IP_VALUE="SERVER_IP"
if command -v ip >/dev/null 2>&1; then
  HOST_IP_VALUE="$(ip route get 1.1.1.1 2>/dev/null | awk '{for (i=1;i<=NF;i++) if ($i=="src") {print $(i+1); exit}}')"
fi
if [[ -z "$HOST_IP_VALUE" || "$HOST_IP_VALUE" == "SERVER_IP" ]] && command -v hostname >/dev/null 2>&1; then
  HOST_IP_VALUE="$(hostname -I 2>/dev/null | awk '{print $1}')"
fi
HOST_IP_VALUE="${HOST_IP_VALUE:-SERVER_IP}"

"${COMPOSE[@]}" pull postgres
"${COMPOSE[@]}" up -d --build
"${COMPOSE[@]}" ps

echo
echo "Service logs:"
"${COMPOSE[@]}" logs --tail=80 service
echo
echo "If this is the first startup, save the generated admin password from the logs above."
echo "Local service URL: http://127.0.0.1:${HOST_HTTP_PORT_VALUE}"
if [[ "$HOST_BIND_ADDR_VALUE" == "127.0.0.1" || "$HOST_BIND_ADDR_VALUE" == "localhost" ]]; then
  echo "Reverse proxy target: http://127.0.0.1:${HOST_HTTP_PORT_VALUE}"
else
  echo "Reverse proxy target for Docker-based 1Panel/OpenResty: http://${HOST_IP_VALUE}:${HOST_HTTP_PORT_VALUE}"
fi
