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
  echo "Edit .env and replace MYSQL_ROOT_PASSWORD and MYSQL_PASSWORD, then run this script again."
  exit 1
fi

if grep -Eq '^(MYSQL_ROOT_PASSWORD|MYSQL_PASSWORD)=change-me-' .env; then
  echo "Refusing to deploy with default database passwords in .env." >&2
  echo "Edit .env and set strong MYSQL_ROOT_PASSWORD and MYSQL_PASSWORD values." >&2
  exit 1
fi

"${COMPOSE[@]}" pull mysql
"${COMPOSE[@]}" up -d --build
"${COMPOSE[@]}" ps

HOST_HTTP_PORT_VALUE="$(grep -E '^HOST_HTTP_PORT=' .env | tail -n 1 | cut -d= -f2- || true)"
HOST_HTTP_PORT_VALUE="${HOST_HTTP_PORT_VALUE:-18080}"

echo
echo "Service logs:"
"${COMPOSE[@]}" logs --tail=80 service
echo
echo "If this is the first startup, save the generated admin password from the logs above."
echo "Local service URL: http://127.0.0.1:${HOST_HTTP_PORT_VALUE}"
