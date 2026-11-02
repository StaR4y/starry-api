#!/usr/bin/env bash
# Starts Starry API from a checkout with Docker Compose. It works wherever Docker does, without root, and creates .env with
# generated passwords on the first run.
#
#   ./scripts/docker-up.sh           runs the published image; without one, Compose builds this checkout
#   ./scripts/docker-up.sh --build   builds this checkout
#
# On a Linux server, scripts/install.sh does this and also installs Docker, upgrades and rolls back.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

env_value() {
  grep -E "^$1=" .env | tail -n 1 | cut -d= -f2- || true
}

create_env_file() {
  command -v openssl >/dev/null 2>&1 || { echo "openssl is required to generate the passwords." >&2; exit 1; }
  local temporary
  temporary="$(mktemp .env.incoming.XXXXXX)"
  chmod 0600 "$temporary"
  sed \
    -e "s/^POSTGRES_BOOTSTRAP_PASSWORD=.*/POSTGRES_BOOTSTRAP_PASSWORD=$(openssl rand -hex 24)/" \
    -e "s/^POSTGRES_MIGRATION_PASSWORD=.*/POSTGRES_MIGRATION_PASSWORD=$(openssl rand -hex 24)/" \
    -e "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=$(openssl rand -hex 24)/" \
    -e "s|^STARRY_WEBUI_URL=.*|STARRY_WEBUI_URL=http://127.0.0.1:18080/#/login|" \
    .env.example > "$temporary"
  mv -f "$temporary" .env
  echo ".env was created with generated database passwords. Keep it: the database only opens with them."
}

check_env_file() {
  local bootstrap migration application
  bootstrap="$(env_value POSTGRES_BOOTSTRAP_PASSWORD)"
  migration="$(env_value POSTGRES_MIGRATION_PASSWORD)"
  application="$(env_value POSTGRES_PASSWORD)"

  if [[ -z "$bootstrap" || -z "$migration" || -z "$application" ]]; then
    echo "Refusing to deploy with missing or empty PostgreSQL passwords in .env." >&2
    exit 1
  fi
  if [[ "$bootstrap" == change-me-* || "$migration" == change-me-* || "$application" == change-me-* ]]; then
    echo "Refusing to deploy with default database passwords in .env." >&2
    exit 1
  fi
  if [[ "$bootstrap" == "$migration" || "$bootstrap" == "$application" || "$migration" == "$application" ]]; then
    echo "Refusing to deploy with reused PostgreSQL passwords in .env." >&2
    exit 1
  fi
}

command -v docker >/dev/null 2>&1 || { echo "Docker is required: https://docs.docker.com/get-docker/" >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "Docker Compose 2 is required." >&2; exit 1; }

[[ -f .env ]] || create_env_file
check_env_file

docker compose up -d "$@"
docker compose ps

host_bind_addr="$(env_value HOST_BIND_ADDR)"
[[ -n "$host_bind_addr" && "$host_bind_addr" != "0.0.0.0" && "$host_bind_addr" != "::" ]] || host_bind_addr="127.0.0.1"
host_http_port="$(env_value HOST_HTTP_PORT)"
cat <<EOF

The service needs about a minute on its first start.
Console:          http://${host_bind_addr}:${host_http_port:-18080}/#/login
Root login info:  ./scripts/starry-api.sh info
Logs:             docker compose logs -f service
EOF
