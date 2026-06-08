#!/usr/bin/env bash
set -euo pipefail

APP_NAME="starry-api"
REPO_URL="${REPO_URL:-https://github.com/StaR4y/starry-api.git}"
BRANCH="${BRANCH:-main}"
INSTALL_DIR="${INSTALL_DIR:-/opt/starry-api}"
HOST_BIND_ADDR="${HOST_BIND_ADDR:-0.0.0.0}"
HOST_HTTP_PORT="${HOST_HTTP_PORT:-18080}"
SERVER_PORT="${SERVER_PORT:-8080}"
HEALTH_WAIT_SECONDS="${HEALTH_WAIT_SECONDS:-300}"
PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-}"
FORCE_ENV="${FORCE_ENV:-false}"
INSTALL_DOCKER="${INSTALL_DOCKER:-true}"

log() {
  printf '[%s] %s\n' "$APP_NAME" "$*"
}

fail() {
  printf '[%s] ERROR: %s\n' "$APP_NAME" "$*" >&2
  exit 1
}

root_cmd() {
  "$@"
}

require_linux() {
  [[ "$(uname -s)" == "Linux" ]] || fail "This installer is for Linux servers."
}

require_root() {
  [[ "${EUID}" -eq 0 ]] || fail "Run with sudo: curl ... | sudo bash"
}

detect_package_manager() {
  if command -v apt-get >/dev/null 2>&1; then
    echo apt
  elif command -v dnf >/dev/null 2>&1; then
    echo dnf
  elif command -v yum >/dev/null 2>&1; then
    echo yum
  else
    echo unknown
  fi
}

install_base_packages() {
  local pm
  pm="$(detect_package_manager)"
  log "Installing base packages with ${pm}..."
  case "$pm" in
    apt)
      root_cmd apt-get update
      root_cmd apt-get install -y ca-certificates curl git openssl
      ;;
    dnf)
      root_cmd dnf install -y ca-certificates curl git openssl
      ;;
    yum)
      root_cmd yum install -y ca-certificates curl git openssl
      ;;
    *)
      fail "Unsupported package manager. Install git, curl, openssl, docker and docker compose manually, then rerun with INSTALL_DOCKER=false."
      ;;
  esac
}

ensure_docker() {
  if command -v docker >/dev/null 2>&1; then
    log "Docker is already installed."
  elif [[ "$INSTALL_DOCKER" == "true" ]]; then
    log "Installing Docker from the official convenience script..."
    curl -fsSL https://get.docker.com -o /tmp/get-docker.sh
    root_cmd sh /tmp/get-docker.sh
  else
    fail "Docker is not installed. Install Docker first or run with INSTALL_DOCKER=true."
  fi

  if command -v systemctl >/dev/null 2>&1; then
    root_cmd systemctl enable --now docker
  else
    root_cmd service docker start >/dev/null 2>&1 || true
  fi
}

ensure_compose() {
  if root_cmd docker compose version >/dev/null 2>&1; then
    log "Docker Compose plugin is available."
    return
  fi
  if command -v docker-compose >/dev/null 2>&1; then
    log "docker-compose is available."
    return
  fi

  local pm
  pm="$(detect_package_manager)"
  log "Installing Docker Compose plugin with ${pm}..."
  case "$pm" in
    apt)
      root_cmd apt-get update
      root_cmd apt-get install -y docker-compose-plugin
      ;;
    dnf)
      root_cmd dnf install -y docker-compose-plugin
      ;;
    yum)
      root_cmd yum install -y docker-compose-plugin
      ;;
    *)
      fail "Docker Compose is not available. Install docker compose manually and rerun."
      ;;
  esac
}

set_compose_cmd() {
  if root_cmd docker compose version >/dev/null 2>&1; then
    COMPOSE_CMD=(docker compose)
    return
  fi
  if command -v docker-compose >/dev/null 2>&1; then
    COMPOSE_CMD=(docker-compose)
    return
  fi
  fail "Docker Compose is not available after installation."
}

prepare_install_dir() {
  log "Preparing ${INSTALL_DIR}..."
  log "Using repository ${REPO_URL} branch ${BRANCH}."
  git ls-remote --heads "$REPO_URL" "$BRANCH" >/dev/null \
    || fail "Repository or branch is not accessible: ${REPO_URL} ${BRANCH}"
  root_cmd mkdir -p "$(dirname "$INSTALL_DIR")"
  if [[ -d "$INSTALL_DIR/.git" ]]; then
    if git -C "$INSTALL_DIR" remote get-url origin >/dev/null 2>&1; then
      git -C "$INSTALL_DIR" remote set-url origin "$REPO_URL"
    else
      git -C "$INSTALL_DIR" remote add origin "$REPO_URL"
    fi
    git -C "$INSTALL_DIR" fetch origin "$BRANCH"
    git -C "$INSTALL_DIR" checkout "$BRANCH"
    git -C "$INSTALL_DIR" pull --ff-only origin "$BRANCH"
  elif [[ -d "$INSTALL_DIR" ]] && [[ -n "$(find "$INSTALL_DIR" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]]; then
    fail "${INSTALL_DIR} exists and is not an empty git checkout."
  else
    root_cmd mkdir -p "$INSTALL_DIR"
    git clone --branch "$BRANCH" "$REPO_URL" "$INSTALL_DIR"
  fi
}

random_secret() {
  openssl rand -hex 24
}

webui_url() {
  if [[ -n "$PUBLIC_BASE_URL" ]]; then
    printf '%s/#/login' "${PUBLIC_BASE_URL%/}"
  else
    printf 'http://%s:%s/#/login' "$(local_access_host)" "$HOST_HTTP_PORT"
  fi
}

local_access_host() {
  if [[ "$HOST_BIND_ADDR" == "0.0.0.0" || "$HOST_BIND_ADDR" == "::" ]]; then
    printf '127.0.0.1'
  else
    printf '%s' "$HOST_BIND_ADDR"
  fi
}

detect_host_ip() {
  local ip_addr=""
  if command -v ip >/dev/null 2>&1; then
    ip_addr="$(ip route get 1.1.1.1 2>/dev/null | awk '{for (i=1;i<=NF;i++) if ($i=="src") {print $(i+1); exit}}')"
  fi
  if [[ -z "$ip_addr" ]] && command -v hostname >/dev/null 2>&1; then
    ip_addr="$(hostname -I 2>/dev/null | awk '{print $1}')"
  fi
  printf '%s' "${ip_addr:-SERVER_IP}"
}

reverse_proxy_target() {
  if [[ "$HOST_BIND_ADDR" == "127.0.0.1" || "$HOST_BIND_ADDR" == "localhost" ]]; then
    printf 'http://127.0.0.1:%s' "$HOST_HTTP_PORT"
  else
    printf 'http://%s:%s' "$(detect_host_ip)" "$HOST_HTTP_PORT"
  fi
}

write_env_file() {
  local env_file="${INSTALL_DIR}/.env"
  if [[ -f "$env_file" && "$FORCE_ENV" != "true" ]]; then
    log "Keeping existing .env. Set FORCE_ENV=true to regenerate it."
    return
  fi

  log "Writing ${env_file} with generated database passwords..."
  cat > "$env_file" <<EOF
HOST_BIND_ADDR=${HOST_BIND_ADDR}
SERVER_PORT=${SERVER_PORT}
HOST_HTTP_PORT=${HOST_HTTP_PORT}

MYSQL_ROOT_PASSWORD=$(random_secret)
MYSQL_IMAGE=mysql:8.0.37-oraclelinux8
MYSQL_DATABASE=kiro_proxy
MYSQL_USERNAME=kiro_proxy
MYSQL_PASSWORD=$(random_secret)

# Runtime variables still use KIRO_* for compatibility with the current Kiro/AWS upstream adapter.
KIRO_ALLOW_ANONYMOUS_PROXY=false
KIRO_ADMIN_SESSION_TTL=12h
KIRO_RATE_LIMIT_PER_MINUTE=120
KIRO_ACCOUNT_SELECTION_STRATEGY=round_robin
KIRO_WEBUI_URL=$(webui_url)
KIRO_TOKEN_REFRESH_INTERVAL_MS=3600000
KIRO_TOKEN_REFRESH_INITIAL_DELAY_MS=60000

JAVA_OPTS=-XX:MaxRAMPercentage=75.0
EOF
  chmod 600 "$env_file"
}

load_runtime_env() {
  local env_file="${INSTALL_DIR}/.env"
  [[ -f "$env_file" ]] || return

  local value
  value="$(grep -E '^HOST_BIND_ADDR=' "$env_file" | tail -n 1 | cut -d= -f2- || true)"
  if [[ -n "$value" ]]; then
    HOST_BIND_ADDR="$value"
  fi
  value="$(grep -E '^HOST_HTTP_PORT=' "$env_file" | tail -n 1 | cut -d= -f2- || true)"
  if [[ -n "$value" ]]; then
    HOST_HTTP_PORT="$value"
  fi
  value="$(grep -E '^SERVER_PORT=' "$env_file" | tail -n 1 | cut -d= -f2- || true)"
  if [[ -n "$value" ]]; then
    SERVER_PORT="$value"
  fi

  log "Using runtime ports from .env: HOST_BIND_ADDR=${HOST_BIND_ADDR}, HOST_HTTP_PORT=${HOST_HTTP_PORT}, SERVER_PORT=${SERVER_PORT}"
  export HOST_BIND_ADDR HOST_HTTP_PORT SERVER_PORT
}

wait_for_health() {
  local url="http://$(local_access_host):${HOST_HTTP_PORT}/actuator/health"
  local attempts=$(( (HEALTH_WAIT_SECONDS + 1) / 2 ))
  local last_response=""
  local status=""
  log "Waiting for service health: ${url}"
  for attempt in $(seq 1 "$attempts"); do
    last_response="$(curl -sS -m 3 -w '\nHTTP_STATUS:%{http_code}' "$url" 2>&1 || true)"
    status="$(printf '%s\n' "$last_response" | sed -n 's/^HTTP_STATUS://p' | tail -n 1)"
    if [[ "$status" == "200" ]]; then
      return 0
    fi
    if (( attempt % 15 == 0 )); then
      log "Health still not ready after $(( attempt * 2 ))s. Last status: ${status:-curl-failed}"
    fi
    sleep 2
  done
  log "Last health response:"
  printf '%s\n' "$last_response" >&2
  return 1
}

deploy_stack() {
  COMPOSE_CMD=()
  set_compose_cmd
  cd "$INSTALL_DIR"

  log "Starting Docker Compose stack..."
  root_cmd "${COMPOSE_CMD[@]}" pull mysql || true
  root_cmd "${COMPOSE_CMD[@]}" up -d --build

  if ! wait_for_health; then
    root_cmd "${COMPOSE_CMD[@]}" ps || true
    root_cmd "${COMPOSE_CMD[@]}" logs --tail=120 mysql || true
    root_cmd "${COMPOSE_CMD[@]}" logs --tail=160 service || true
    fail "Service did not become healthy in time."
  fi

  log "Recent service logs. Save the first admin password if this is the first startup."
  root_cmd "${COMPOSE_CMD[@]}" logs --tail=120 service || true

  cat <<EOF

Deployment complete.

Install dir:        ${INSTALL_DIR}
Local WebUI:        http://$(local_access_host):${HOST_HTTP_PORT}/#/login
Local API docs:     http://$(local_access_host):${HOST_HTTP_PORT}/docs
Reverse proxy to:   $(reverse_proxy_target)

Common commands:
  cd ${INSTALL_DIR}
  docker compose logs -f service
  docker compose restart service
  docker compose down

If using 1Panel/Nginx, create a reverse proxy site with target:
  $(reverse_proxy_target)

If your 1Panel OpenResty runs in Docker, keep HOST_BIND_ADDR=0.0.0.0 and
use the server intranet/public IP target shown above. If Nginx runs directly
on the host, you can set HOST_BIND_ADDR=127.0.0.1 and proxy to localhost.
EOF
}

main() {
  require_linux
  require_root
  install_base_packages
  ensure_docker
  ensure_compose
  prepare_install_dir
  write_env_file
  load_runtime_env
  deploy_stack
}

main "$@"
