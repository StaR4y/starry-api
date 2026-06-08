#!/usr/bin/env bash
set -euo pipefail

APP_NAME="starry-api"
REPO_URL="${REPO_URL:-https://github.com/StaR4y/starry-api.git}"
BRANCH="${BRANCH:-main}"

default_repo_raw_base() {
  local repo="${REPO_URL%.git}"
  case "$repo" in
    https://github.com/*)
      printf 'https://raw.githubusercontent.com/%s/%s' "${repo#https://github.com/}" "$BRANCH"
      ;;
    git@github.com:*)
      repo="${repo#git@github.com:}"
      printf 'https://raw.githubusercontent.com/%s/%s' "$repo" "$BRANCH"
      ;;
    *)
      printf 'https://raw.githubusercontent.com/StaR4y/starry-api/%s' "$BRANCH"
      ;;
  esac
}

REPO_RAW_BASE="${REPO_RAW_BASE:-$(default_repo_raw_base)}"
BUNDLE_URL="${BUNDLE_URL:-${REPO_RAW_BASE%/}/artifacts/starry-api-bundle.tar.gz}"
BUNDLE_CHECKSUM_URL="${BUNDLE_CHECKSUM_URL:-${BUNDLE_URL}.sha256}"
ARTIFACT_URL="${ARTIFACT_URL:-${REPO_RAW_BASE%/}/artifacts/starry-api.jar}"
CHECKSUM_URL="${CHECKSUM_URL:-${REPO_RAW_BASE%/}/artifacts/starry-api.jar.sha256}"
BUILD_INFO_URL="${BUILD_INFO_URL:-${REPO_RAW_BASE%/}/artifacts/build-info.json}"
INSTALL_DIR="${INSTALL_DIR:-/opt/starry-api}"
HOST_BIND_ADDR="${HOST_BIND_ADDR:-0.0.0.0}"
HOST_HTTP_PORT="${HOST_HTTP_PORT:-18080}"
SERVER_PORT="${SERVER_PORT:-8080}"
HEALTH_WAIT_SECONDS="${HEALTH_WAIT_SECONDS:-300}"
PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-}"
FORCE_ENV="${FORCE_ENV:-false}"
INSTALL_DOCKER="${INSTALL_DOCKER:-true}"
MYSQL_IMAGE="${MYSQL_IMAGE:-mysql:8.0.37-oraclelinux8}"

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
      root_cmd apt-get install -y ca-certificates curl openssl
      ;;
    dnf)
      root_cmd dnf install -y ca-certificates curl openssl
      ;;
    yum)
      root_cmd yum install -y ca-certificates curl openssl
      ;;
    *)
      fail "Unsupported package manager. Install curl, openssl, docker and docker compose manually, then rerun with INSTALL_DOCKER=false."
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
    COMPOSE_CMD=(docker compose)
    return
  fi
  if command -v docker-compose >/dev/null 2>&1; then
    COMPOSE_CMD=(docker-compose)
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

  if root_cmd docker compose version >/dev/null 2>&1; then
    COMPOSE_CMD=(docker compose)
  elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE_CMD=(docker-compose)
  else
    fail "Docker Compose is not available after installation."
  fi
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

download_file() {
  local url="$1"
  local output="$2"
  if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    curl -fsSL -H "Authorization: Bearer ${GITHUB_TOKEN}" "$url" -o "$output"
  else
    curl -fsSL "$url" -o "$output"
  fi
}

download_failure_hint() {
  if [[ -z "${GITHUB_TOKEN:-}" ]]; then
    log "Download failed without GITHUB_TOKEN. If the repository is private, GitHub returns 404 for raw files."
    log "Use a fine-grained GitHub token with read-only Contents permission and pass GITHUB_TOKEN to this script."
  else
    log "Download failed even though GITHUB_TOKEN was provided. Check that the token can read ${REPO_URL}."
  fi
}

download_required_file() {
  local label="$1"
  local url="$2"
  local output="$3"
  log "Downloading ${label}: ${url}"
  if ! download_file "$url" "$output"; then
    download_failure_hint
    fail "Could not download ${label}: ${url}"
  fi
}

random_secret() {
  openssl rand -hex 24
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

webui_url() {
  if [[ -n "$PUBLIC_BASE_URL" ]]; then
    printf '%s/#/login' "${PUBLIC_BASE_URL%/}"
  else
    printf 'http://%s:%s/#/login' "$(local_access_host)" "$HOST_HTTP_PORT"
  fi
}

prepare_install_dir() {
  log "Preparing ${INSTALL_DIR}..."
  log "Using prebuilt artifact base ${REPO_RAW_BASE}."
  root_cmd mkdir -p "$INSTALL_DIR/app" "$INSTALL_DIR/data/mysql"
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
MYSQL_IMAGE=${MYSQL_IMAGE}
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
  [[ -n "$value" ]] && HOST_BIND_ADDR="$value"
  value="$(grep -E '^HOST_HTTP_PORT=' "$env_file" | tail -n 1 | cut -d= -f2- || true)"
  [[ -n "$value" ]] && HOST_HTTP_PORT="$value"
  value="$(grep -E '^SERVER_PORT=' "$env_file" | tail -n 1 | cut -d= -f2- || true)"
  [[ -n "$value" ]] && SERVER_PORT="$value"

  export HOST_BIND_ADDR HOST_HTTP_PORT SERVER_PORT
  log "Using runtime ports from .env: HOST_BIND_ADDR=${HOST_BIND_ADDR}, HOST_HTTP_PORT=${HOST_HTTP_PORT}, SERVER_PORT=${SERVER_PORT}"
}

write_compose_file() {
  cat > "${INSTALL_DIR}/docker-compose.artifact.yml" <<'EOF'
services:
  mysql:
    image: ${MYSQL_IMAGE:-mysql:8.0.37-oraclelinux8}
    container_name: starryapi-mysql
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: ${MYSQL_DATABASE:-kiro_proxy}
      MYSQL_USER: ${MYSQL_USERNAME:-kiro_proxy}
      MYSQL_PASSWORD: ${MYSQL_PASSWORD}
      TZ: Asia/Shanghai
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_unicode_ci
    volumes:
      - ./data/mysql:/var/lib/mysql
    healthcheck:
      test: ["CMD-SHELL", "mysqladmin ping -h 127.0.0.1 -u\"$${MYSQL_USER}\" -p\"$${MYSQL_PASSWORD}\" --silent"]
      interval: 10s
      timeout: 5s
      retries: 20
      start_period: 30s
    networks:
      - starryapi

  service:
    image: eclipse-temurin:25-jre
    container_name: starryapi-service
    restart: unless-stopped
    depends_on:
      mysql:
        condition: service_healthy
    working_dir: /app
    volumes:
      - ./app/starry-api.jar:/app/app.jar:ro
    environment:
      SERVER_PORT: ${SERVER_PORT:-8080}
      MYSQL_HOST: mysql
      MYSQL_PORT: 3306
      MYSQL_DATABASE: ${MYSQL_DATABASE:-kiro_proxy}
      MYSQL_USERNAME: ${MYSQL_USERNAME:-kiro_proxy}
      MYSQL_PASSWORD: ${MYSQL_PASSWORD}
      KIRO_ALLOW_ANONYMOUS_PROXY: ${KIRO_ALLOW_ANONYMOUS_PROXY:-false}
      KIRO_ADMIN_SESSION_TTL: ${KIRO_ADMIN_SESSION_TTL:-12h}
      KIRO_RATE_LIMIT_PER_MINUTE: ${KIRO_RATE_LIMIT_PER_MINUTE:-120}
      KIRO_ACCOUNT_SELECTION_STRATEGY: ${KIRO_ACCOUNT_SELECTION_STRATEGY:-round_robin}
      KIRO_WEBUI_URL: ${KIRO_WEBUI_URL:-}
      KIRO_ADMIN_TOKEN: ${KIRO_ADMIN_TOKEN:-}
      JAVA_OPTS: ${JAVA_OPTS:-}
    ports:
      - "${HOST_BIND_ADDR:-127.0.0.1}:${HOST_HTTP_PORT:-18080}:${SERVER_PORT:-8080}"
    command: ["sh", "-c", "exec java $${JAVA_OPTS:-} -jar /app/app.jar"]
    networks:
      - starryapi

networks:
  starryapi:
    name: starryapi
EOF
}

download_artifact() {
  local tmp_dir tmp_jar tmp_sha tmp_info expected actual
  tmp_dir="$(mktemp -d)"
  tmp_jar="${tmp_dir}/starry-api.jar"
  tmp_sha="${tmp_dir}/starry-api.jar.sha256"
  tmp_info="${tmp_dir}/build-info.json"

  download_required_file "artifact jar" "$ARTIFACT_URL" "$tmp_jar"
  download_required_file "artifact checksum" "$CHECKSUM_URL" "$tmp_sha"
  download_file "$BUILD_INFO_URL" "$tmp_info" || true

  expected="$(awk '{print $1}' "$tmp_sha" | head -n 1)"
  actual="$(sha256_file "$tmp_jar")"
  [[ -n "$expected" ]] || fail "Checksum file is empty."
  [[ "$expected" == "$actual" ]] || fail "Artifact checksum mismatch. expected=${expected} actual=${actual}"

  if [[ -f "${INSTALL_DIR}/app/starry-api.jar" ]]; then
    cp "${INSTALL_DIR}/app/starry-api.jar" "${INSTALL_DIR}/app/starry-api.jar.previous"
  fi
  mv "$tmp_jar" "${INSTALL_DIR}/app/starry-api.jar"
  cp "$tmp_sha" "${INSTALL_DIR}/app/starry-api.jar.sha256"
  if [[ -s "$tmp_info" ]]; then
    cp "$tmp_info" "${INSTALL_DIR}/app/build-info.json"
  fi
  rm -rf "$tmp_dir"
  log "Installed artifact with SHA256 ${actual}"
}

download_bundle() {
  local tmp_dir tmp_bundle tmp_sha extract_dir expected actual
  tmp_dir="$(mktemp -d)"
  tmp_bundle="${tmp_dir}/starry-api-bundle.tar.gz"
  tmp_sha="${tmp_dir}/starry-api-bundle.tar.gz.sha256"
  extract_dir="${tmp_dir}/extract"
  mkdir -p "$extract_dir"

  log "Downloading deployment bundle: ${BUNDLE_URL}"
  if ! download_file "$BUNDLE_URL" "$tmp_bundle"; then
    log "Deployment bundle is not available; trying single-jar artifact fallback."
    download_failure_hint
    rm -rf "$tmp_dir"
    return 1
  fi

  log "Downloading deployment bundle checksum: ${BUNDLE_CHECKSUM_URL}"
  if ! download_file "$BUNDLE_CHECKSUM_URL" "$tmp_sha"; then
    log "Deployment bundle checksum is not available; trying single-jar artifact fallback."
    rm -rf "$tmp_dir"
    return 1
  fi

  expected="$(awk '{print $1}' "$tmp_sha" | head -n 1)"
  actual="$(sha256_file "$tmp_bundle")"
  [[ -n "$expected" ]] || fail "Bundle checksum file is empty."
  [[ "$expected" == "$actual" ]] || fail "Bundle checksum mismatch. expected=${expected} actual=${actual}"

  tar -xzf "$tmp_bundle" -C "$extract_dir"
  [[ -f "${extract_dir}/app/starry-api.jar" ]] || fail "Bundle does not contain app/starry-api.jar."
  [[ -f "${extract_dir}/docker-compose.artifact.yml" ]] || fail "Bundle does not contain docker-compose.artifact.yml."

  if [[ -f "${INSTALL_DIR}/app/starry-api.jar" ]]; then
    cp "${INSTALL_DIR}/app/starry-api.jar" "${INSTALL_DIR}/app/starry-api.jar.previous"
  fi
  cp "${extract_dir}/app/starry-api.jar" "${INSTALL_DIR}/app/starry-api.jar"
  cp "${extract_dir}/app/starry-api.jar.sha256" "${INSTALL_DIR}/app/starry-api.jar.sha256"
  cp "${extract_dir}/docker-compose.artifact.yml" "${INSTALL_DIR}/docker-compose.artifact.yml"
  [[ -f "${extract_dir}/build-info.json" ]] && cp "${extract_dir}/build-info.json" "${INSTALL_DIR}/app/build-info.json"
  [[ -f "${extract_dir}/sha256sums.txt" ]] && cp "${extract_dir}/sha256sums.txt" "${INSTALL_DIR}/app/sha256sums.txt"
  [[ -f "${extract_dir}/starry-api-webui.tar.gz" ]] && cp "${extract_dir}/starry-api-webui.tar.gz" "${INSTALL_DIR}/app/starry-api-webui.tar.gz"
  [[ -f "${extract_dir}/starry-api-webui.tar.gz.sha256" ]] && cp "${extract_dir}/starry-api-webui.tar.gz.sha256" "${INSTALL_DIR}/app/starry-api-webui.tar.gz.sha256"

  rm -rf "$tmp_dir"
  log "Installed deployment bundle with SHA256 ${actual}"
}

wait_for_health() {
  local url="http://$(local_access_host):${HOST_HTTP_PORT}/actuator/health"
  local attempts=$(( (HEALTH_WAIT_SECONDS + 1) / 2 ))
  local last_response="" status=""
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
  cd "$INSTALL_DIR"
  log "Starting Docker Compose stack from prebuilt artifact..."
  root_cmd "${COMPOSE_CMD[@]}" -f docker-compose.artifact.yml pull mysql service || true
  root_cmd "${COMPOSE_CMD[@]}" -f docker-compose.artifact.yml up -d

  if ! wait_for_health; then
    root_cmd "${COMPOSE_CMD[@]}" -f docker-compose.artifact.yml ps || true
    root_cmd "${COMPOSE_CMD[@]}" -f docker-compose.artifact.yml logs --tail=120 mysql || true
    root_cmd "${COMPOSE_CMD[@]}" -f docker-compose.artifact.yml logs --tail=160 service || true
    fail "Service did not become healthy in time."
  fi

  root_cmd "${COMPOSE_CMD[@]}" -f docker-compose.artifact.yml logs --tail=120 service || true

  cat <<EOF

Deployment complete.

Install dir:        ${INSTALL_DIR}
Artifact file:      ${INSTALL_DIR}/app/starry-api.jar
Local WebUI:        http://$(local_access_host):${HOST_HTTP_PORT}/#/login
Local API docs:     http://$(local_access_host):${HOST_HTTP_PORT}/docs
Reverse proxy to:   $(reverse_proxy_target)

Common commands:
  cd ${INSTALL_DIR}
  docker compose -f docker-compose.artifact.yml logs -f service
  docker compose -f docker-compose.artifact.yml restart service
  docker compose -f docker-compose.artifact.yml down
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
  write_compose_file
  download_bundle || download_artifact
  deploy_stack
}

main "$@"
