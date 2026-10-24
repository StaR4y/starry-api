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
POSTGRES_INIT_URL="${POSTGRES_INIT_URL:-${REPO_RAW_BASE%/}/docker/postgresql/init/001-starry-api-roles.sh}"
RECOVERY_TOOL_URL="${RECOVERY_TOOL_URL:-${REPO_RAW_BASE%/}/artifacts/runtime/recover-data-management.sh}"
RECOVERY_TOOL_CHECKSUM_URL="${RECOVERY_TOOL_CHECKSUM_URL:-${RECOVERY_TOOL_URL}.sha256}"
MIHOMO_ENTRYPOINT_URL="${MIHOMO_ENTRYPOINT_URL:-${REPO_RAW_BASE%/}/docker/egress/mihomo-entrypoint.sh}"
# The "starry-api" administration command (sign-in information, Root password reset). The bundle carries it; these
# URLs are used only when the bundle does not, or when the single-jar fallback is taken.
CLI_TOOL_URL="${CLI_TOOL_URL:-${REPO_RAW_BASE%/}/artifacts/starry-api.sh}"
CLI_TOOL_CHECKSUM_URL="${CLI_TOOL_CHECKSUM_URL:-${CLI_TOOL_URL}.sha256}"
INSTALL_DIR="${INSTALL_DIR:-/opt/starry-api}"
GITHUB_TOKEN_FILE="${GITHUB_TOKEN_FILE:-${INSTALL_DIR}/.github-token}"
# Where the "starry-api" command is linked so that it is on the PATH; empty skips the link. The command itself is always
# installed as ${INSTALL_DIR}/starry-api.
CLI_LINK_PATH="${CLI_LINK_PATH-/usr/local/bin/starry-api}"
HOST_BIND_ADDR="${HOST_BIND_ADDR:-0.0.0.0}"
HOST_HTTP_PORT="${HOST_HTTP_PORT:-18080}"
SERVER_PORT="${SERVER_PORT:-8080}"
HEALTH_WAIT_SECONDS="${HEALTH_WAIT_SECONDS:-300}"
PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-}"
FORCE_ENV="${FORCE_ENV:-false}"
INSTALL_DOCKER="${INSTALL_DOCKER:-true}"
# ENABLE_MIHOMO controls whether the optional Mihomo (Clash.Meta) egress component is deployed next to the service.
# Deploying it means COMPOSE_PROFILES=mihomo and a generated STARRY_EGRESS_CONTROLLER_SECRET in .env plus the official
# metacubex/mihomo image (about 20 MB of memory while idle). It does not switch Mihomo on: an administrator does that
# in the console (System settings > Outbound proxies).
#   auto  (default) a fresh install deploys it; an existing .env is left as it is, so an upgrade never gains a
#         container silently; a release whose bundle has no mihomo service is installed without it.
#   true  also adds it to an existing .env (keeping the old file as .env.previous); fails if the bundle has no
#         mihomo service.
#   false never adds it. Anything already in .env stays; remove COMPOSE_PROFILES and STARRY_EGRESS_CONTROLLER_SECRET
#         from .env and run "docker compose rm -sf mihomo" to remove it.
ENABLE_MIHOMO="${ENABLE_MIHOMO:-auto}"
# Set when this run put Mihomo into a new .env on its own (ENABLE_MIHOMO=auto), which is the only case in which a
# bundle without the mihomo service is skipped instead of refused.
MIHOMO_AUTO_DEPLOYED=false
ARTIFACT_UPDATED=false
ARTIFACT_ROLLBACK_AVAILABLE=false
COMPOSE_ROLLBACK_AVAILABLE=false
POSTGRES_INIT_ROLLBACK_AVAILABLE=false
RECOVERY_TOOL_ROLLBACK_AVAILABLE=false
ENV_ROLLBACK_AVAILABLE=false
ENV_UPDATED=false
MIHOMO_STACK_STARTED=false
RECOVERY_TOOL_UPDATED=false
STACK_MUTATION_STARTED=false
DEPLOYMENT_COMMITTED=false
ROLLBACK_COMPLETED=false
ROLLBACK_ATTEMPTED=false
INSTALLED_ARTIFACT_CHECKSUM=""
# True once this run has installed the administration command; CLI_COMMAND is how the deployment summary names it.
CLI_TOOL_INSTALLED=false
CLI_COMMAND=""

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
  local install_script
  if command -v docker >/dev/null 2>&1; then
    log "Docker is already installed."
  elif [[ "$INSTALL_DOCKER" == "true" ]]; then
    log "Installing Docker from the official convenience script..."
    install_script="$(mktemp "${TMPDIR:-/tmp}/starry-api-get-docker.XXXXXX")"
    chmod 0600 "$install_script"
    if ! curl -fsSL https://get.docker.com -o "$install_script"; then
      rm -f -- "$install_script"
      fail "Could not download the Docker installation script."
    fi
    if ! root_cmd sh "$install_script"; then
      rm -f -- "$install_script"
      fail "Docker installation failed."
    fi
    rm -f -- "$install_script"
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

require_compose_inline_dockerfile() {
  local version major minor
  version="$(root_cmd "${COMPOSE_CMD[@]}" version --short 2>/dev/null || true)"
  version="${version#v}"
  version="${version%%-*}"
  IFS=. read -r major minor _ <<< "$version"
  if [[ ! "${major:-}" =~ ^[0-9]+$ || ! "${minor:-}" =~ ^[0-9]+$ ]] \
    || (( major < 2 || (major == 2 && minor < 17) )); then
    fail "Docker Compose 2.17 or newer is required for the PostgreSQL client runtime build. Found: ${version:-unknown}"
  fi
  log "Docker Compose ${version} supports the inline runtime build."
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

atomic_install_file() {
  local source="$1"
  local destination="$2"
  local mode="${3:-0644}"
  local temporary

  if ! temporary="$(mktemp "${destination}.incoming.XXXXXX")"; then
    return 1
  fi
  if ! cp -- "$source" "$temporary"; then
    rm -f -- "$temporary"
    return 1
  fi
  if ! chmod "$mode" "$temporary"; then
    rm -f -- "$temporary"
    return 1
  fi
  if ! mv -f -- "$temporary" "$destination"; then
    rm -f -- "$temporary"
    return 1
  fi
}

backup_current_artifact() {
  local current="${INSTALL_DIR}/app/starry-api.jar"
  local previous="${INSTALL_DIR}/app/starry-api.jar.previous"
  local previous_checksum="${INSTALL_DIR}/app/starry-api.jar.previous.sha256"
  local current_compose="${INSTALL_DIR}/docker-compose.artifact.yml"
  local previous_compose="${INSTALL_DIR}/docker-compose.artifact.yml.previous"
  local current_init="${INSTALL_DIR}/postgres-init/001-starry-api-roles.sh"
  local previous_init="${INSTALL_DIR}/postgres-init/001-starry-api-roles.sh.previous"
  local current_recovery="${INSTALL_DIR}/runtime/recover-data-management.sh"
  local current_recovery_checksum="${INSTALL_DIR}/runtime/recover-data-management.sh.sha256"
  local previous_recovery="${INSTALL_DIR}/runtime/recover-data-management.sh.previous"
  local previous_recovery_checksum="${INSTALL_DIR}/runtime/recover-data-management.sh.previous.sha256"
  local current_env="${INSTALL_DIR}/.env"
  local previous_env="${INSTALL_DIR}/.env.previous"
  local checksum checksum_tmp recovery_checksum recovery_checksum_tmp

  if [[ ! -f "$current" || "$ARTIFACT_ROLLBACK_AVAILABLE" == "true" ]]; then
    return
  fi

  atomic_install_file "$current" "$previous" 0644 \
    || fail "Could not preserve the current application jar for rollback."
  checksum="$(sha256_file "$previous")"
  checksum_tmp="$(mktemp "${previous_checksum}.incoming.XXXXXX")" \
    || fail "Could not prepare the rollback checksum."
  printf '%s  %s\n' "$checksum" "starry-api.jar" > "$checksum_tmp" \
    || fail "Could not write the rollback checksum."
  chmod 0644 "$checksum_tmp" \
    || fail "Could not secure the rollback checksum."
  mv -f -- "$checksum_tmp" "$previous_checksum" \
    || fail "Could not activate the rollback checksum."

  if [[ -f "$current_compose" ]]; then
    atomic_install_file "$current_compose" "$previous_compose" 0644 \
      || fail "Could not preserve the current Docker Compose configuration for rollback."
    COMPOSE_ROLLBACK_AVAILABLE=true
  else
    rm -f -- "$previous_compose"
  fi
  if [[ -f "$current_init" ]]; then
    atomic_install_file "$current_init" "$previous_init" 0755 \
      || fail "Could not preserve the current PostgreSQL bootstrap for rollback."
    POSTGRES_INIT_ROLLBACK_AVAILABLE=true
  else
    rm -f -- "$previous_init"
  fi
  if [[ -f "$current_recovery" ]]; then
    atomic_install_file "$current_recovery" "$previous_recovery" 0555 \
      || fail "Could not preserve the current data-management recovery tool for rollback."
    recovery_checksum="$(sha256_file "$previous_recovery")"
    recovery_checksum_tmp="$(mktemp "${previous_recovery_checksum}.incoming.XXXXXX")" \
      || fail "Could not prepare the recovery-tool rollback checksum."
    printf '%s  %s\n' "$recovery_checksum" "recover-data-management.sh" > "$recovery_checksum_tmp" \
      || fail "Could not write the recovery-tool rollback checksum."
    chmod 0644 "$recovery_checksum_tmp" \
      || fail "Could not secure the recovery-tool rollback checksum."
    mv -f -- "$recovery_checksum_tmp" "$previous_recovery_checksum" \
      || fail "Could not activate the recovery-tool rollback checksum."
    RECOVERY_TOOL_ROLLBACK_AVAILABLE=true
  else
    rm -f -- "$previous_recovery" "$previous_recovery_checksum" "$current_recovery_checksum"
  fi
  if [[ -f "$current_env" ]]; then
    atomic_install_file "$current_env" "$previous_env" 0600 \
      || fail "Could not preserve the current environment file for rollback."
    ENV_ROLLBACK_AVAILABLE=true
  else
    rm -f -- "$previous_env"
  fi
  ARTIFACT_ROLLBACK_AVAILABLE=true
}

install_verified_artifact() {
  local source="$1"
  local checksum_file="$2"
  local expected actual

  expected="$(awk '{print $1}' "$checksum_file" | head -n 1)"
  actual="$(sha256_file "$source")"
  [[ -n "$expected" ]] || fail "Artifact checksum file is empty."
  [[ "$expected" == "$actual" ]] \
    || fail "Artifact checksum mismatch. expected=${expected} actual=${actual}"

  backup_current_artifact
  atomic_install_file "$checksum_file" "${INSTALL_DIR}/app/starry-api.jar.sha256" 0644 \
    || fail "Could not install the artifact checksum atomically."
  atomic_install_file "$source" "${INSTALL_DIR}/app/starry-api.jar" 0644 \
    || fail "Could not install the application jar atomically."
  ARTIFACT_UPDATED=true
  INSTALLED_ARTIFACT_CHECKSUM="$actual"
}

install_verified_recovery_tool() {
  local source="$1"
  local checksum_file="$2"
  local expected actual

  expected="$(awk '{print $1}' "$checksum_file" | head -n 1)"
  actual="$(sha256_file "$source")"
  [[ -n "$expected" ]] || fail "Recovery tool checksum file is empty."
  [[ "$expected" == "$actual" ]] \
    || fail "Recovery tool checksum mismatch. expected=${expected} actual=${actual}"

  backup_current_artifact
  RECOVERY_TOOL_UPDATED=true
  atomic_install_file "$checksum_file" "${INSTALL_DIR}/runtime/recover-data-management.sh.sha256" 0644 \
    || fail "Could not install the recovery tool checksum atomically."
  atomic_install_file "$source" "${INSTALL_DIR}/runtime/recover-data-management.sh" 0555 \
    || fail "Could not install the data-management recovery tool atomically."
}

install_verified_cli_tool() {
  local source="$1"
  local checksum_file="$2"
  local expected actual

  expected="$(awk '{print $1}' "$checksum_file" | head -n 1)"
  actual="$(sha256_file "$source")"
  [[ -n "$expected" ]] || fail "Administration command checksum file is empty."
  [[ "$expected" == "$actual" ]] \
    || fail "Administration command checksum mismatch. expected=${expected} actual=${actual}"

  atomic_install_file "$source" "${INSTALL_DIR}/starry-api" 0755 \
    || fail "Could not install the administration command atomically."
  CLI_TOOL_INSTALLED=true
}

# The administration command is a convenience for operators, so a release that does not offer it (or a download that
# fails) only skips it with a notice. A script that does not match its checksum is never installed and stops the
# deployment, like any other artifact.
download_and_install_cli_tool() {
  local tmp_dir tmp_script tmp_sha

  tmp_dir="$(mktemp -d)"
  tmp_script="${tmp_dir}/starry-api.sh"
  tmp_sha="${tmp_dir}/starry-api.sh.sha256"
  log "Downloading the administration command: ${CLI_TOOL_URL}"
  if download_file "$CLI_TOOL_URL" "$tmp_script" && download_file "$CLI_TOOL_CHECKSUM_URL" "$tmp_sha"; then
    install_verified_cli_tool "$tmp_script" "$tmp_sha"
  else
    log "This release does not offer the starry-api administration command; skipping it."
  fi
  rm -rf "$tmp_dir"
}

# True for a symlink that points at the starry-api script of some Starry API installation directory (the one a
# previous run linked); anything else at the link path is somebody else's file.
cli_link_is_ours() {
  local link="$1"
  local target

  [[ -L "$link" ]] || return 1
  target="$(readlink "$link")"
  [[ "$target" == /* && "$(basename "$target")" == "starry-api" ]] || return 1
  [[ -f "$(dirname "$target")/docker-compose.artifact.yml" || -f "$(dirname "$target")/docker-compose.yml" ]]
}

# Puts "starry-api" on the PATH by linking CLI_LINK_PATH to ${INSTALL_DIR}/starry-api, and works out how the deployment
# summary names the command. It only creates a missing link or replaces one that already points into a Starry API
# installation; a regular file, a directory or any other link at that path is left alone.
link_cli_tool() {
  local target="${INSTALL_DIR}/starry-api"
  local link="$CLI_LINK_PATH"
  local temporary

  CLI_COMMAND=""
  [[ -f "$target" ]] || return 0
  CLI_COMMAND="$target"
  if [[ -z "$link" ]]; then
    return 0
  fi
  if [[ ! -d "$(dirname "$link")" ]]; then
    log "$(dirname "$link") does not exist; not linking the starry-api command. Run ${target} instead."
    return 0
  fi
  if [[ -L "$link" && "$(readlink "$link")" == "$target" ]]; then
    CLI_COMMAND="starry-api"
    return 0
  fi
  if [[ -e "$link" || -L "$link" ]] && ! cli_link_is_ours "$link"; then
    log "Leaving ${link} alone because it is not a link to a Starry API installation. Run ${target} instead."
    return 0
  fi
  temporary="${link}.incoming.$$"
  rm -f -- "$temporary"
  if ln -s "$target" "$temporary" && mv -f -- "$temporary" "$link"; then
    CLI_COMMAND="starry-api"
    log "Linked ${link} to ${target}."
  else
    rm -f -- "$temporary"
    log "Could not link ${link}; run ${target} instead."
  fi
}

ensure_cli_tool() {
  if [[ "$CLI_TOOL_INSTALLED" != "true" ]]; then
    download_and_install_cli_tool
  fi
  link_cli_tool
}

rollback_installed_artifact() {
  local previous="${INSTALL_DIR}/app/starry-api.jar.previous"
  local previous_checksum="${INSTALL_DIR}/app/starry-api.jar.previous.sha256"
  local current_compose="${INSTALL_DIR}/docker-compose.artifact.yml"
  local previous_compose="${INSTALL_DIR}/docker-compose.artifact.yml.previous"
  local current_init="${INSTALL_DIR}/postgres-init/001-starry-api-roles.sh"
  local previous_init="${INSTALL_DIR}/postgres-init/001-starry-api-roles.sh.previous"
  local current_recovery="${INSTALL_DIR}/runtime/recover-data-management.sh"
  local current_recovery_checksum="${INSTALL_DIR}/runtime/recover-data-management.sh.sha256"
  local previous_recovery="${INSTALL_DIR}/runtime/recover-data-management.sh.previous"
  local previous_recovery_checksum="${INSTALL_DIR}/runtime/recover-data-management.sh.previous.sha256"
  local current_env="${INSTALL_DIR}/.env"
  local previous_env="${INSTALL_DIR}/.env.previous"
  local retry_env="${INSTALL_DIR}/.env.postgresql-retry"
  local expected actual recovery_expected recovery_actual

  if [[ "$ARTIFACT_ROLLBACK_AVAILABLE" != "true" ]]; then
    return 1
  fi
  [[ -f "$previous" && -f "$previous_checksum" ]] || return 1

  expected="$(awk '{print $1}' "$previous_checksum" | head -n 1)"
  actual="$(sha256_file "$previous")"
  [[ -n "$expected" && "$expected" == "$actual" ]] || return 1
  [[ "$COMPOSE_ROLLBACK_AVAILABLE" == "true" && -f "$previous_compose" ]] || return 1
  if [[ "$RECOVERY_TOOL_ROLLBACK_AVAILABLE" == "true" ]]; then
    [[ -f "$previous_recovery" && -f "$previous_recovery_checksum" ]] || return 1
    recovery_expected="$(awk '{print $1}' "$previous_recovery_checksum" | head -n 1)"
    recovery_actual="$(sha256_file "$previous_recovery")"
    [[ -n "$recovery_expected" && "$recovery_expected" == "$recovery_actual" ]] || return 1
  fi

  atomic_install_file "$previous_compose" "$current_compose" 0644 || return 1
  if [[ "$POSTGRES_INIT_ROLLBACK_AVAILABLE" == "true" ]]; then
    [[ -f "$previous_init" ]] || return 1
    atomic_install_file "$previous_init" "$current_init" 0755 || return 1
  else
    rm -f -- "$current_init" || return 1
  fi
  if [[ "$RECOVERY_TOOL_ROLLBACK_AVAILABLE" == "true" ]]; then
    atomic_install_file "$previous_recovery_checksum" "$current_recovery_checksum" 0644 || return 1
    atomic_install_file "$previous_recovery" "$current_recovery" 0555 || return 1
  elif [[ "$RECOVERY_TOOL_UPDATED" == "true" ]]; then
    rm -f -- "$current_recovery" "$current_recovery_checksum" || return 1
  fi
  if [[ "$ENV_ROLLBACK_AVAILABLE" == "true" ]]; then
    [[ -f "$previous_env" ]] || return 1
    if [[ "$ENV_UPDATED" == "true" ]] && postgres_cluster_initialized; then
      atomic_install_file "$current_env" "$retry_env" 0600 || return 1
      log "Retained the initialized PostgreSQL cluster credentials in ${retry_env} for a safe retry."
    fi
    atomic_install_file "$previous_env" "$current_env" 0600 || return 1
  elif [[ "$ENV_UPDATED" == "true" ]]; then
    rm -f -- "$current_env" || return 1
  fi
  atomic_install_file "$previous_checksum" "${INSTALL_DIR}/app/starry-api.jar.sha256" 0644 || return 1
  atomic_install_file "$previous" "${INSTALL_DIR}/app/starry-api.jar" 0644 || return 1
  ARTIFACT_UPDATED=false
  RECOVERY_TOOL_UPDATED=false
  ENV_UPDATED=false
  log "Restored the previous environment, application jar, Compose configuration, PostgreSQL bootstrap, and recovery tool after the failed deployment."
}

deployment_exit_handler() {
  local status="$1"
  trap - EXIT
  if [[ "$status" -eq 0 || "$DEPLOYMENT_COMMITTED" == "true" || "$ROLLBACK_COMPLETED" == "true" ]]; then
    exit "$status"
  fi
  if [[ "$ROLLBACK_ATTEMPTED" == "true" ]]; then
    log "Automatic rollback was already attempted; inspect ${INSTALL_DIR} before retrying."
    exit "$status"
  fi
  if [[ "$ARTIFACT_ROLLBACK_AVAILABLE" != "true" ]]; then
    exit "$status"
  fi

  ROLLBACK_ATTEMPTED=true
  log "Deployment failed before commit; restoring the previous deployment unit..."
  if ! rollback_installed_artifact; then
    log "Automatic deployment-file rollback failed. Inspect ${INSTALL_DIR} before retrying."
    exit "$status"
  fi
  if [[ "$STACK_MUTATION_STARTED" == "true" ]]; then
    cd "$INSTALL_DIR" || exit "$status"
    if root_cmd "${COMPOSE_CMD[@]}" -f docker-compose.artifact.yml up -d --build --force-recreate --remove-orphans \
      && discard_unwanted_mihomo \
      && wait_for_health; then
      ROLLBACK_COMPLETED=true
      log "The previous deployment unit is healthy after automatic rollback."
    else
      log "The previous deployment files were restored, but the old stack did not recover automatically."
    fi
  fi
  exit "$status"
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
  root_cmd mkdir -p \
    "$INSTALL_DIR/app" \
    "$INSTALL_DIR/data/postgresql" \
    "$INSTALL_DIR/data/backups" \
    "$INSTALL_DIR/data/security" \
    "$INSTALL_DIR/data/plugins" \
    "$INSTALL_DIR/data/documentation" \
    "$INSTALL_DIR/postgres-init" \
    "$INSTALL_DIR/runtime"
  root_cmd chmod 0700 "$INSTALL_DIR/data/security"
}

postgres_cluster_initialized() {
  [[ -s "${INSTALL_DIR}/data/postgresql/PG_VERSION" ]]
}

env_file_value() {
  local env_file="$1"
  local name="$2"
  grep -E "^${name}=" "$env_file" | tail -n 1 | cut -d= -f2- || true
}

validate_database_secrets() {
  local env_file="$1"
  local bootstrap migration application

  bootstrap="$(env_file_value "$env_file" POSTGRES_BOOTSTRAP_PASSWORD)"
  migration="$(env_file_value "$env_file" POSTGRES_MIGRATION_PASSWORD)"
  application="$(env_file_value "$env_file" POSTGRES_PASSWORD)"

  [[ -n "$bootstrap" && -n "$migration" && -n "$application" ]] \
    || fail "Existing .env must contain non-empty bootstrap, migration, and application PostgreSQL passwords."
  [[ "$bootstrap" != change-me-* && "$migration" != change-me-* && "$application" != change-me-* ]] \
    || fail "Refusing to deploy with default PostgreSQL passwords in .env."
  [[ "$bootstrap" != "$migration" && "$bootstrap" != "$application" && "$migration" != "$application" ]] \
    || fail "PostgreSQL bootstrap, migration, and application passwords must be distinct."
}

validate_enable_mihomo() {
  if [[ "$ENABLE_MIHOMO" != "auto" && "$ENABLE_MIHOMO" != "true" && "$ENABLE_MIHOMO" != "false" ]]; then
    fail "ENABLE_MIHOMO must be auto, true or false."
  fi
}

# The value of a key as Compose reads it from an env file: the last assignment, without surrounding quotes.
env_file_plain_value() {
  local value
  value="$(env_file_value "$1" "$2")"
  value="${value%\"}"
  value="${value#\"}"
  value="${value%\'}"
  value="${value#\'}"
  printf '%s' "$value"
}

# True when COMPOSE_PROFILES in the env file selects the mihomo profile (or every profile).
mihomo_profile_listed() {
  local env_file="$1"
  local profiles item
  local -a items=()
  [[ -f "$env_file" ]] || return 1
  profiles="$(env_file_plain_value "$env_file" COMPOSE_PROFILES)"
  IFS=, read -r -a items <<< "$profiles" || true
  for item in ${items[@]+"${items[@]}"}; do
    item="${item//[[:space:]]/}"
    if [[ "$item" == "mihomo" || "$item" == "*" ]]; then
      return 0
    fi
  done
  return 1
}

# Replaces every assignment of a key by a single one at the end of the file.
set_env_value() {
  local file="$1"
  local key="$2"
  local value="$3"
  local temporary

  temporary="$(mktemp "${file}.incoming.XXXXXX")" || return 1
  if ! { grep -v -E "^${key}=" "$file" || true; printf '%s=%s\n' "$key" "$value"; } > "$temporary"; then
    rm -f -- "$temporary"
    return 1
  fi
  if ! mv -f -- "$temporary" "$file"; then
    rm -f -- "$temporary"
    return 1
  fi
}

# An existing controller secret must be something the Mihomo entrypoint accepts; it is never printed.
validate_mihomo_secret() {
  local secret
  secret="$(env_file_plain_value "$1" STARRY_EGRESS_CONTROLLER_SECRET)"
  if [[ -n "$secret" && ! "$secret" =~ ^[A-Za-z0-9_-]+$ ]]; then
    fail "STARRY_EGRESS_CONTROLLER_SECRET in .env may only contain letters, digits, '_' and '-', otherwise Mihomo refuses to start."
  fi
}

# Makes the env file deploy Mihomo: the profile is selected (which starts the container) and a controller secret exists
# (which makes it available to the backend). The backend switch is not touched: an administrator switches Mihomo on in
# the console, and an existing STARRY_EGRESS_MIHOMO_ENABLED stays what it was (the initial value of that switch).
# Settings that are already right are left as they are, so applying it again changes nothing. The secret is never printed.
apply_mihomo_env_settings() {
  local file="$1"
  local profiles secret

  if ! mihomo_profile_listed "$file"; then
    profiles="$(env_file_plain_value "$file" COMPOSE_PROFILES)"
    set_env_value "$file" COMPOSE_PROFILES "${profiles:+${profiles},}mihomo" || return 1
  fi
  if [[ -z "$(env_file_plain_value "$file" STARRY_EGRESS_CONTROLLER_SECRET)" ]]; then
    secret="$(random_secret)" || return 1
    [[ -n "$secret" ]] || return 1
    set_env_value "$file" STARRY_EGRESS_CONTROLLER_SECRET "$secret" || return 1
  fi
}

# Takes back what a fresh install added on its own: the comment, the profile and the secret.
remove_auto_mihomo_from_env_file() {
  local file="$1"
  local temporary

  temporary="$(mktemp "${file}.incoming.XXXXXX")" || return 1
  if ! chmod 0600 "$temporary" \
    || ! { grep -v -E '^(# Optional Mihomo egress component|COMPOSE_PROFILES=|STARRY_EGRESS_CONTROLLER_SECRET=)' "$file" || true; } > "$temporary" \
    || ! mv -f -- "$temporary" "$file"; then
    rm -f -- "$temporary"
    return 1
  fi
}

# Adds Mihomo to the .env that is already in place. When something has to change, the current file is first kept as
# .env.previous (unless the caller has just done that), and the new one replaces it atomically.
enable_mihomo_in_env_file() {
  local env_file="$1"
  local preserve_previous="$2"
  local env_tmp

  validate_mihomo_secret "$env_file"
  env_tmp="$(mktemp "${env_file}.incoming.XXXXXX")" \
    || fail "Could not prepare the updated .env file."
  if ! chmod 0600 "$env_tmp" || ! cp -- "$env_file" "$env_tmp" || ! apply_mihomo_env_settings "$env_tmp"; then
    rm -f -- "$env_tmp"
    fail "Could not add Mihomo to .env."
  fi
  if cmp -s "$env_file" "$env_tmp"; then
    rm -f -- "$env_tmp"
    log "Mihomo is already deployed by .env."
    return
  fi
  if [[ "$preserve_previous" == "true" ]]; then
    if ! atomic_install_file "$env_file" "${INSTALL_DIR}/.env.previous" 0600; then
      rm -f -- "$env_tmp"
      fail "Could not preserve the existing .env file."
    fi
  fi
  mv -f -- "$env_tmp" "$env_file" || { rm -f -- "$env_tmp"; fail "Could not activate the updated .env file."; }
  log "Added Mihomo to .env (COMPOSE_PROFILES, STARRY_EGRESS_CONTROLLER_SECRET); switch it on in the console."
}

# One of: deployed, not-deployed, incomplete. Deployed needs the profile (the container) and a secret (what makes it
# available to the backend); whether it is switched on is an administrator's choice in the console, not part of .env.
mihomo_state() {
  local env_file="${INSTALL_DIR}/.env"
  local listed=false secret=false

  mihomo_profile_listed "$env_file" && listed=true
  if [[ -f "$env_file" ]]; then
    [[ -n "$(env_file_plain_value "$env_file" STARRY_EGRESS_CONTROLLER_SECRET)" ]] && secret=true
  fi
  if [[ "$listed" == "true" && "$secret" == "true" ]]; then
    printf 'deployed'
  elif [[ "$listed" == "false" && "$secret" == "false" ]]; then
    printf 'not-deployed'
  else
    printf 'incomplete'
  fi
}

mihomo_state_line() {
  case "$(mihomo_state)" in
    deployed)
      printf 'deployed - switch it on in the console under 系统设置 → 出站代理 (System settings > Outbound proxies)'
      if [[ "$(env_file_plain_value "${INSTALL_DIR}/.env" STARRY_EGRESS_MIHOMO_ENABLED)" == "true" ]]; then
        printf '; STARRY_EGRESS_MIHOMO_ENABLED=true in .env keeps it on until that switch is used'
      fi
      ;;
    incomplete)
      printf 'INCOMPLETE - .env needs both COMPOSE_PROFILES=mihomo and STARRY_EGRESS_CONTROLLER_SECRET'
      ;;
    *)
      printf 'not deployed (rerun with ENABLE_MIHOMO=true to deploy it; see docs/ACCOUNT_EGRESS.md)'
      ;;
  esac
}

# The Mihomo container mounts this script from the install directory. A missing source would make Docker create a
# directory in its place, so the script is installed whenever the bundle carries it and fetched when Mihomo needs it.
install_mihomo_entrypoint() {
  local source="$1"
  local target="${INSTALL_DIR}/docker/egress/mihomo-entrypoint.sh"

  if [[ -d "$target" ]]; then
    rmdir -- "$target" 2>/dev/null \
      || fail "${target} is a directory; remove it so the Mihomo entrypoint script can be installed."
  fi
  mkdir -p "${INSTALL_DIR}/docker/egress" || fail "Could not create ${INSTALL_DIR}/docker/egress."
  atomic_install_file "$source" "$target" 0644 \
    || fail "Could not install the Mihomo entrypoint atomically."
}

ensure_mihomo_entrypoint() {
  local target="${INSTALL_DIR}/docker/egress/mihomo-entrypoint.sh"
  local temporary

  [[ ! -f "$target" ]] || return 0
  mihomo_profile_listed "${INSTALL_DIR}/.env" || return 0
  mkdir -p "${INSTALL_DIR}/docker/egress" || fail "Could not create ${INSTALL_DIR}/docker/egress."
  temporary="$(mktemp "${target}.download.XXXXXX")" \
    || fail "Could not prepare the Mihomo entrypoint download."
  download_required_file "Mihomo entrypoint script" "$MIHOMO_ENTRYPOINT_URL" "$temporary"
  install_mihomo_entrypoint "$temporary"
  rm -f -- "$temporary"
}

# Fails before the stack is touched when Mihomo is selected but the installed Compose file cannot run it, instead of
# leaving the backend waiting for a controller that was never started. The one exception is a fresh install that
# picked Mihomo on its own (ENABLE_MIHOMO=auto): an older release without the service is installed without Mihomo.
ensure_mihomo_support() {
  mihomo_profile_listed "${INSTALL_DIR}/.env" || return 0
  if ! grep -q '^  mihomo:' "${INSTALL_DIR}/docker-compose.artifact.yml"; then
    if [[ "$MIHOMO_AUTO_DEPLOYED" == "true" ]]; then
      remove_auto_mihomo_from_env_file "${INSTALL_DIR}/.env" || fail "Could not take Mihomo out of .env."
      MIHOMO_AUTO_DEPLOYED=false
      log "The downloaded deployment bundle does not define the mihomo service (an older release), so Mihomo is not deployed. Rerun with ENABLE_MIHOMO=true once a release that includes it is installed."
      return 0
    fi
    fail "Mihomo is selected in .env, but the downloaded deployment bundle does not define the mihomo service. Use a release that includes it, or remove COMPOSE_PROFILES and STARRY_EGRESS_CONTROLLER_SECRET from .env."
  fi
  ensure_mihomo_entrypoint
}

# Compose does not count a container of an inactive profile as an orphan, so a Mihomo container started by a deployment
# that was rolled back (and whose .env no longer selects the profile) has to be removed explicitly.
discard_unwanted_mihomo() {
  [[ "$MIHOMO_STACK_STARTED" == "true" ]] || return 0
  if ! mihomo_profile_listed "${INSTALL_DIR}/.env"; then
    root_cmd "${COMPOSE_CMD[@]}" -f docker-compose.artifact.yml rm -sf mihomo || true
  fi
  return 0
}

load_github_token() {
  if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    log "Using GitHub token from environment."
    return
  fi

  if [[ ! -f "$GITHUB_TOKEN_FILE" ]]; then
    return
  fi

  GITHUB_TOKEN="$(tr -d '[:space:]' < "$GITHUB_TOKEN_FILE")"
  export GITHUB_TOKEN
  if [[ -n "$GITHUB_TOKEN" ]]; then
    log "Using GitHub token from ${GITHUB_TOKEN_FILE}."
  else
    log "GitHub token file is empty: ${GITHUB_TOKEN_FILE}"
  fi
}

write_env_file() {
  local env_file="${INSTALL_DIR}/.env"
  local retry_env_file="${INSTALL_DIR}/.env.postgresql-retry"
  local env_tmp bootstrap_password migration_password application_password

  if [[ "$FORCE_ENV" != "true" && "$FORCE_ENV" != "false" ]]; then
    fail "FORCE_ENV must be true or false."
  fi
  validate_enable_mihomo

  if [[ -f "$env_file" && "$FORCE_ENV" != "true" ]]; then
    validate_database_secrets "$env_file"
    log "Keeping existing .env. Set FORCE_ENV=true to regenerate it."
    if [[ "$ENABLE_MIHOMO" == "true" ]]; then
      enable_mihomo_in_env_file "$env_file" true
    elif [[ "$ENABLE_MIHOMO" == "auto" ]] && ! mihomo_profile_listed "$env_file"; then
      log "Not adding Mihomo to the existing .env (ENABLE_MIHOMO=auto only deploys it on a fresh install). Rerun with ENABLE_MIHOMO=true to add it."
    fi
    return
  fi

  if postgres_cluster_initialized; then
    if [[ -f "$retry_env_file" ]]; then
      validate_database_secrets "$retry_env_file"
      if [[ -f "$env_file" ]]; then
        atomic_install_file "$env_file" "${INSTALL_DIR}/.env.previous" 0600 \
          || fail "Could not preserve the existing .env file."
      fi
      atomic_install_file "$retry_env_file" "$env_file" 0600 \
        || fail "Could not restore the PostgreSQL retry environment."
      ENV_UPDATED=true
      log "Reusing PostgreSQL credentials retained from the previous failed deployment."
      if [[ "$ENABLE_MIHOMO" == "true" ]]; then
        enable_mihomo_in_env_file "$env_file" false
      fi
      return
    fi
    fail "Refusing to generate new database passwords for an initialized PostgreSQL cluster. Restore the matching .env, or rotate the roles in PostgreSQL and update .env manually."
  fi

  if [[ -f "$env_file" ]]; then
    atomic_install_file "$env_file" "${INSTALL_DIR}/.env.previous" 0600 \
      || fail "Could not preserve the existing .env file."
  fi

  bootstrap_password="$(random_secret)"
  migration_password="$(random_secret)"
  application_password="$(random_secret)"
  env_tmp="$(mktemp "${env_file}.incoming.XXXXXX")" \
    || fail "Could not prepare the new .env file."
  chmod 0600 "$env_tmp" || fail "Could not secure the new .env file."

  log "Writing ${env_file} with generated database passwords..."
  cat > "$env_tmp" <<EOF
HOST_BIND_ADDR=${HOST_BIND_ADDR}
SERVER_PORT=${SERVER_PORT}
HOST_HTTP_PORT=${HOST_HTTP_PORT}

POSTGRES_BOOTSTRAP_PASSWORD=${bootstrap_password}
POSTGRES_MIGRATION_PASSWORD=${migration_password}
POSTGRES_PASSWORD=${application_password}
POSTGRES_SSL_MODE=disable
POSTGRES_SHM_SIZE=256mb
POSTGRES_MAX_CONNECTIONS=100
POSTGRES_SHARED_BUFFERS=256MB
POSTGRES_EFFECTIVE_CACHE_SIZE=768MB
POSTGRES_WORK_MEM=4MB
POSTGRES_MAINTENANCE_WORK_MEM=64MB
POSTGRES_AUTOVACUUM_WORK_MEM=64MB
POSTGRES_AUTOVACUUM_MAX_WORKERS=4
POSTGRES_AUTOVACUUM_NAPTIME=30s
POSTGRES_DEFAULT_STATISTICS_TARGET=200
POSTGRES_WAL_COMPRESSION=on
POSTGRES_MIN_WAL_SIZE=512MB
POSTGRES_MAX_WAL_SIZE=2GB
POSTGRES_CHECKPOINT_COMPLETION_TARGET=0.9
POSTGRES_TRACK_IO_TIMING=on
POSTGRES_IDLE_IN_TRANSACTION_SESSION_TIMEOUT=5min
POSTGRES_POOL_INITIAL_SIZE=4
POSTGRES_POOL_MAX_SIZE=20
POSTGRES_POOL_MAX_ACQUIRE_TIME=5s
POSTGRES_POOL_MAX_CREATE_CONNECTION_TIME=5s
POSTGRES_POOL_MAX_LIFE_TIME=2h
POSTGRES_PREPARED_STATEMENT_CACHE_QUERIES=256
POSTGRES_STATEMENT_TIMEOUT=PT2M
POSTGRES_LOCK_WAIT_TIMEOUT=PT5S
FLYWAY_ENABLED=true
FLYWAY_CONNECT_RETRIES=10
SPRING_CODEC_MAX_IN_MEMORY_SIZE=16MB

STARRY_MAX_IMPORT_BYTES=268435456
STARRY_DATA_TRANSFER_PROCESS_TIMEOUT=30m
STARRY_DATA_MANAGEMENT_REQUIRE_INITIALIZED=false
STARRY_PAYMENT_RECONCILIATION_INTERVAL=PT30S
STARRY_PAYMENT_RECONCILIATION_INITIAL_DELAY=PT30S
STARRY_PAYMENT_PROVIDER_RECONCILIATION_INTERVAL=PT30S
STARRY_PAYMENT_PROVIDER_RECONCILIATION_INITIAL_DELAY=PT45S
STARRY_STATS_AGGREGATION_ENABLED=true
STARRY_STATS_AGGREGATION_INTERVAL=PT1M
STARRY_STATS_AGGREGATION_INITIAL_DELAY=PT30S
STARRY_STATS_AGGREGATION_MAX_ADVANCE_HOURS=1
STARRY_STATS_AGGREGATION_LOOKBACK_HOURS=6
STARRY_STATS_AGGREGATION_MAX_BATCHES_PER_RUN=6
STARRY_STATS_MAINTENANCE_CRON="0 25 3 * * *"
STARRY_STATS_PARTITION_MONTHS_AHEAD=12
STARRY_STATS_RAW_RETENTION_MONTHS=4
STARRY_STATS_RAW_DEFAULT_DELETE_BATCH_SIZE=10000
STARRY_STATS_RAW_DEFAULT_DELETE_MAX_BATCHES=20
STARRY_STATS_HOURLY_RETENTION_DAYS=90
STARRY_STATS_HOURLY_DELETE_BATCH_SIZE=10000
STARRY_STATS_HOURLY_DELETE_MAX_BATCHES=20
STARRY_STATS_USAGE_EVENT_RETENTION_DAYS=400
STARRY_STATS_USAGE_EVENT_DELETE_BATCH_SIZE=10000
STARRY_STATS_USAGE_EVENT_DELETE_MAX_BATCHES=100
STARRY_ACCOUNT_ALLOCATION_MAINTENANCE_ENABLED=true
STARRY_ACCOUNT_ALLOCATION_MAINTENANCE_CRON="0 35 * * * *"
STARRY_ACCOUNT_ALLOCATION_LEASE_RETENTION_DAYS=7
STARRY_ACCOUNT_ALLOCATION_EVENT_RETENTION_DAYS=90
STARRY_ACCOUNT_ALLOCATION_DELETE_BATCH_SIZE=10000
STARRY_ACCOUNT_ALLOCATION_DELETE_MAX_BATCHES=20

# Gateway settings. Installs made before the rename used KIRO_* names; the service still reads those when the
# STARRY_* name is absent.
STARRY_ALLOW_ANONYMOUS_PROXY=false
STARRY_ADMIN_SESSION_TTL=12h
STARRY_RATE_LIMIT_PER_MINUTE=120
STARRY_ACCOUNT_SELECTION_STRATEGY=round_robin
STARRY_REQUEST_TIMEOUT=300s
STARRY_MAX_RETRIES=2
STARRY_BASE_COOLDOWN=60s
STARRY_QUOTA_RESET=1h
STARRY_PROBABILISTIC_RETRY=0.1
STARRY_MAX_REQUEST_BODY_BYTES=10485760
STARRY_SESSION_AFFINITY_ENABLED=true
STARRY_SESSION_AFFINITY_TTL=10m
STARRY_SESSION_AFFINITY_MAXIMUM_SIZE=100000
STARRY_WEBUI_URL=$(webui_url)
STARRY_TOKEN_REFRESH_INTERVAL_MS=3600000
STARRY_TOKEN_REFRESH_INITIAL_DELAY_MS=60000
STARRY_KIRO_PREFERRED_ENDPOINT=codewhisperer

JAVA_OPTS="-Xms64m -Xmx768m -XX:MaxDirectMemorySize=256m -XX:+ExitOnOutOfMemoryError -XX:G1PeriodicGCInterval=300000 -XX:-G1PeriodicGCInvokesConcurrent -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30"
EOF
  if [[ "$ENABLE_MIHOMO" != "false" ]]; then
    if [[ "$ENABLE_MIHOMO" == "true" ]]; then
      log "Deploying the optional Mihomo component (ENABLE_MIHOMO=true)."
    else
      log "Deploying the optional Mihomo component (ENABLE_MIHOMO=auto); an administrator switches it on in the console."
      MIHOMO_AUTO_DEPLOYED=true
    fi
    printf '\n# Optional Mihomo egress component, deployed idle; switch it on in the console (System settings > Outbound proxies). Remove COMPOSE_PROFILES and STARRY_EGRESS_CONTROLLER_SECRET and run "docker compose rm -sf mihomo" to remove it.\n' >> "$env_tmp" \
      || { rm -f -- "$env_tmp"; fail "Could not write the Mihomo settings."; }
    if ! apply_mihomo_env_settings "$env_tmp"; then
      rm -f -- "$env_tmp"
      fail "Could not write the Mihomo settings."
    fi
  fi
  mv -f -- "$env_tmp" "$env_file" || fail "Could not activate the new .env file."
  ENV_UPDATED=true
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
  postgres:
    image: postgres:17-bookworm
    container_name: starryapi-postgres
    restart: unless-stopped
    environment:
      POSTGRES_DB: starry_api
      POSTGRES_USER: starry_api_bootstrap
      POSTGRES_PASSWORD: ${POSTGRES_BOOTSTRAP_PASSWORD}
      STARRY_POSTGRES_MIGRATION_PASSWORD: ${POSTGRES_MIGRATION_PASSWORD}
      STARRY_POSTGRES_APP_PASSWORD: ${POSTGRES_PASSWORD}
      POSTGRES_INITDB_ARGS: --encoding=UTF8 --locale=C.UTF-8 --data-checksums
      TZ: Asia/Shanghai
    volumes:
      - ./data/postgresql:/var/lib/postgresql/data
      - ./postgres-init/001-starry-api-roles.sh:/docker-entrypoint-initdb.d/001-starry-api-roles.sh:ro
    command:
      - postgres
      - -c
      - max_connections=${POSTGRES_MAX_CONNECTIONS:-100}
      - -c
      - shared_buffers=${POSTGRES_SHARED_BUFFERS:-256MB}
      - -c
      - effective_cache_size=${POSTGRES_EFFECTIVE_CACHE_SIZE:-768MB}
      - -c
      - work_mem=${POSTGRES_WORK_MEM:-4MB}
      - -c
      - maintenance_work_mem=${POSTGRES_MAINTENANCE_WORK_MEM:-64MB}
      - -c
      - autovacuum_work_mem=${POSTGRES_AUTOVACUUM_WORK_MEM:-64MB}
      - -c
      - autovacuum_max_workers=${POSTGRES_AUTOVACUUM_MAX_WORKERS:-4}
      - -c
      - autovacuum_naptime=${POSTGRES_AUTOVACUUM_NAPTIME:-30s}
      - -c
      - default_statistics_target=${POSTGRES_DEFAULT_STATISTICS_TARGET:-200}
      - -c
      - wal_compression=${POSTGRES_WAL_COMPRESSION:-on}
      - -c
      - min_wal_size=${POSTGRES_MIN_WAL_SIZE:-512MB}
      - -c
      - max_wal_size=${POSTGRES_MAX_WAL_SIZE:-2GB}
      - -c
      - checkpoint_completion_target=${POSTGRES_CHECKPOINT_COMPLETION_TARGET:-0.9}
      - -c
      - track_io_timing=${POSTGRES_TRACK_IO_TIMING:-on}
      - -c
      - idle_in_transaction_session_timeout=${POSTGRES_IDLE_IN_TRANSACTION_SESSION_TIMEOUT:-5min}
    shm_size: ${POSTGRES_SHM_SIZE:-256mb}
    stop_grace_period: 1m
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -h 127.0.0.1 -U \"$${POSTGRES_USER}\" -d \"$${POSTGRES_DB}\""]
      interval: 10s
      timeout: 5s
      retries: 20
      start_period: 20s
    networks:
      - starryapi

  service:
    build:
      context: ./runtime
      # Always check for a newer base image so JDK and CA-certificate fixes arrive with the next deployment.
      pull: true
      dockerfile_inline: |
        FROM eclipse-temurin:25-jre-noble
        RUN set -eux; \
            apt-get update; \
            apt-get install -y --no-install-recommends ca-certificates curl jq; \
            install -d -m 0755 /usr/share/postgresql-common/pgdg; \
            curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc; \
            . /etc/os-release; \
            echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt $${VERSION_CODENAME}-pgdg main" > /etc/apt/sources.list.d/pgdg.list; \
            apt-get update; \
            apt-get install -y --no-install-recommends postgresql-client-17; \
            rm -rf /var/lib/apt/lists/*
        # CVE-2026-91403: eight eMudhra intermediate CAs whose private keys can be recomputed chain to emSign ECC Root CA - G3
        # and to AAA Certificate Services, and no JDK release distrusts them yet. None of the upstreams this gateway calls
        # chains to these roots (Sectigo sites validate through the USERTrust roots), so drop them from the JRE trust store.
        # Remove this step once the base image ships a JDK with the fix.
        RUN set -eu; \
            list() { keytool -J-Duser.language=en -list -v -cacerts -storepass changeit; }; \
            test "$$(list | grep -c '^Alias name: ')" -gt 100; \
            list | awk '/^Alias name: /{alias=substr($$0,13)} /^Owner: .*(emSign|CN=AAA Certificate Services,)/{print alias}' \
                | while IFS= read -r alias; do keytool -delete -noprompt -cacerts -storepass changeit -alias "$$alias"; done; \
            ! list | grep -E '^Owner: .*(emSign|CN=AAA Certificate Services,)'
        COPY --chmod=0555 recover-data-management.sh /app/bin/recover-data-management
    image: starryapi-runtime:java25-pg17
    container_name: starryapi-service
    restart: unless-stopped
    # The JVM is capped (JAVA_OPTS) and the container too, so a runaway heap ends in an exit and a restart
    # instead of a hung process. 2g leaves room for the 768m heap, direct buffers, metaspace and thread stacks.
    mem_limit: ${SERVICE_MEM_LIMIT:-2g}
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS -o /dev/null --max-time 4 http://127.0.0.1:${SERVER_PORT:-8080}/health || exit 1"]
      interval: 15s
      timeout: 6s
      retries: 4
      start_period: 90s
    depends_on:
      postgres:
        condition: service_healthy
    working_dir: /app
    volumes:
      - ./app/starry-api.jar:/app/app.jar:ro
      - ./data/backups:/app/backups
      - ./data/security:/app/security
      - ./data/plugins:/app/plugins
      - ./data/documentation:/app/documentation
    environment:
      SERVER_PORT: ${SERVER_PORT:-8080}
      SERVER_ADDRESS: 0.0.0.0
      POSTGRES_HOST: postgres
      POSTGRES_PORT: 5432
      POSTGRES_DATABASE: starry_api
      POSTGRES_USERNAME: starry_api
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      POSTGRES_MIGRATION_USERNAME: starry_api_migration
      POSTGRES_MIGRATION_PASSWORD: ${POSTGRES_MIGRATION_PASSWORD}
      POSTGRES_SSL_MODE: ${POSTGRES_SSL_MODE:-disable}
      POSTGRES_POOL_INITIAL_SIZE: ${POSTGRES_POOL_INITIAL_SIZE:-4}
      POSTGRES_POOL_MAX_SIZE: ${POSTGRES_POOL_MAX_SIZE:-20}
      POSTGRES_POOL_MAX_ACQUIRE_TIME: ${POSTGRES_POOL_MAX_ACQUIRE_TIME:-5s}
      POSTGRES_POOL_MAX_CREATE_CONNECTION_TIME: ${POSTGRES_POOL_MAX_CREATE_CONNECTION_TIME:-5s}
      POSTGRES_POOL_MAX_LIFE_TIME: ${POSTGRES_POOL_MAX_LIFE_TIME:-2h}
      POSTGRES_PREPARED_STATEMENT_CACHE_QUERIES: ${POSTGRES_PREPARED_STATEMENT_CACHE_QUERIES:-256}
      POSTGRES_STATEMENT_TIMEOUT: ${POSTGRES_STATEMENT_TIMEOUT:-PT2M}
      POSTGRES_LOCK_WAIT_TIMEOUT: ${POSTGRES_LOCK_WAIT_TIMEOUT:-PT5S}
      FLYWAY_ENABLED: ${FLYWAY_ENABLED:-true}
      FLYWAY_CONNECT_RETRIES: ${FLYWAY_CONNECT_RETRIES:-10}
      SPRING_CODEC_MAX_IN_MEMORY_SIZE: ${SPRING_CODEC_MAX_IN_MEMORY_SIZE:-16MB}
      STARRY_ALLOW_ANONYMOUS_PROXY: ${STARRY_ALLOW_ANONYMOUS_PROXY:-${KIRO_ALLOW_ANONYMOUS_PROXY:-false}}
      STARRY_ADMIN_SESSION_TTL: ${STARRY_ADMIN_SESSION_TTL:-${KIRO_ADMIN_SESSION_TTL:-12h}}
      STARRY_RATE_LIMIT_PER_MINUTE: ${STARRY_RATE_LIMIT_PER_MINUTE:-${KIRO_RATE_LIMIT_PER_MINUTE:-120}}
      STARRY_ACCOUNT_SELECTION_STRATEGY: ${STARRY_ACCOUNT_SELECTION_STRATEGY:-${KIRO_ACCOUNT_SELECTION_STRATEGY:-round_robin}}
      STARRY_REQUEST_TIMEOUT: ${STARRY_REQUEST_TIMEOUT:-${KIRO_REQUEST_TIMEOUT:-300s}}
      STARRY_MAX_RETRIES: ${STARRY_MAX_RETRIES:-${KIRO_MAX_RETRIES:-2}}
      STARRY_BASE_COOLDOWN: ${STARRY_BASE_COOLDOWN:-${KIRO_BASE_COOLDOWN:-60s}}
      STARRY_QUOTA_RESET: ${STARRY_QUOTA_RESET:-${KIRO_QUOTA_RESET:-1h}}
      STARRY_PROBABILISTIC_RETRY: ${STARRY_PROBABILISTIC_RETRY:-${KIRO_PROBABILISTIC_RETRY:-0.1}}
      STARRY_MAX_REQUEST_BODY_BYTES: ${STARRY_MAX_REQUEST_BODY_BYTES:-${KIRO_MAX_REQUEST_BODY_BYTES:-10485760}}
      STARRY_SESSION_AFFINITY_ENABLED: ${STARRY_SESSION_AFFINITY_ENABLED:-${KIRO_SESSION_AFFINITY_ENABLED:-true}}
      STARRY_SESSION_AFFINITY_TTL: ${STARRY_SESSION_AFFINITY_TTL:-${KIRO_SESSION_AFFINITY_TTL:-10m}}
      STARRY_SESSION_AFFINITY_MAXIMUM_SIZE: ${STARRY_SESSION_AFFINITY_MAXIMUM_SIZE:-${KIRO_SESSION_AFFINITY_MAXIMUM_SIZE:-100000}}
      STARRY_WEBUI_URL: ${STARRY_WEBUI_URL:-${KIRO_WEBUI_URL:-}}
      STARRY_ADMIN_TOKEN: ${STARRY_ADMIN_TOKEN:-${KIRO_ADMIN_TOKEN:-}}
      STARRY_TOKEN_REFRESH_INTERVAL_MS: ${STARRY_TOKEN_REFRESH_INTERVAL_MS:-${KIRO_TOKEN_REFRESH_INTERVAL_MS:-3600000}}
      STARRY_TOKEN_REFRESH_INITIAL_DELAY_MS: ${STARRY_TOKEN_REFRESH_INITIAL_DELAY_MS:-${KIRO_TOKEN_REFRESH_INITIAL_DELAY_MS:-60000}}
      STARRY_KIRO_PREFERRED_ENDPOINT: ${STARRY_KIRO_PREFERRED_ENDPOINT:-${KIRO_PREFERRED_ENDPOINT:-codewhisperer}}
      STARRY_PLUGINS_DISABLED: ${STARRY_PLUGINS_DISABLED:-false}
      STARRY_PLUGIN_DIR: /app/plugins
      STARRY_DOCUMENTATION_ATTACHMENT_DIR: /app/documentation
      STARRY_BACKUP_DIR: /app/backups
      STARRY_MAX_IMPORT_BYTES: ${STARRY_MAX_IMPORT_BYTES:-268435456}
      STARRY_DATA_TRANSFER_PROCESS_TIMEOUT: ${STARRY_DATA_TRANSFER_PROCESS_TIMEOUT:-30m}
      STARRY_DATA_MANAGEMENT_SECURITY_FILE: /app/security/data-management.json
      STARRY_DATA_MANAGEMENT_RECOVERY_KEY_FILE: /app/security/recovery.key
      STARRY_DATA_MANAGEMENT_REQUIRE_INITIALIZED: ${STARRY_DATA_MANAGEMENT_REQUIRE_INITIALIZED:-false}
      STARRY_PAYMENT_RECONCILIATION_INTERVAL: ${STARRY_PAYMENT_RECONCILIATION_INTERVAL:-PT30S}
      STARRY_PAYMENT_RECONCILIATION_INITIAL_DELAY: ${STARRY_PAYMENT_RECONCILIATION_INITIAL_DELAY:-PT30S}
      STARRY_PAYMENT_PROVIDER_RECONCILIATION_INTERVAL: ${STARRY_PAYMENT_PROVIDER_RECONCILIATION_INTERVAL:-PT30S}
      STARRY_PAYMENT_PROVIDER_RECONCILIATION_INITIAL_DELAY: ${STARRY_PAYMENT_PROVIDER_RECONCILIATION_INITIAL_DELAY:-PT45S}
      STARRY_STATS_AGGREGATION_ENABLED: ${STARRY_STATS_AGGREGATION_ENABLED:-true}
      STARRY_STATS_AGGREGATION_INTERVAL: ${STARRY_STATS_AGGREGATION_INTERVAL:-PT1M}
      STARRY_STATS_AGGREGATION_INITIAL_DELAY: ${STARRY_STATS_AGGREGATION_INITIAL_DELAY:-PT30S}
      STARRY_STATS_AGGREGATION_MAX_ADVANCE_HOURS: ${STARRY_STATS_AGGREGATION_MAX_ADVANCE_HOURS:-1}
      STARRY_STATS_AGGREGATION_LOOKBACK_HOURS: ${STARRY_STATS_AGGREGATION_LOOKBACK_HOURS:-6}
      STARRY_STATS_AGGREGATION_MAX_BATCHES_PER_RUN: ${STARRY_STATS_AGGREGATION_MAX_BATCHES_PER_RUN:-6}
      STARRY_STATS_MAINTENANCE_CRON: "${STARRY_STATS_MAINTENANCE_CRON:-0 25 3 * * *}"
      STARRY_STATS_PARTITION_MONTHS_AHEAD: ${STARRY_STATS_PARTITION_MONTHS_AHEAD:-12}
      STARRY_STATS_RAW_RETENTION_MONTHS: ${STARRY_STATS_RAW_RETENTION_MONTHS:-4}
      STARRY_STATS_RAW_DEFAULT_DELETE_BATCH_SIZE: ${STARRY_STATS_RAW_DEFAULT_DELETE_BATCH_SIZE:-10000}
      STARRY_STATS_RAW_DEFAULT_DELETE_MAX_BATCHES: ${STARRY_STATS_RAW_DEFAULT_DELETE_MAX_BATCHES:-20}
      STARRY_STATS_HOURLY_RETENTION_DAYS: ${STARRY_STATS_HOURLY_RETENTION_DAYS:-90}
      STARRY_STATS_HOURLY_DELETE_BATCH_SIZE: ${STARRY_STATS_HOURLY_DELETE_BATCH_SIZE:-10000}
      STARRY_STATS_HOURLY_DELETE_MAX_BATCHES: ${STARRY_STATS_HOURLY_DELETE_MAX_BATCHES:-20}
      STARRY_STATS_USAGE_EVENT_RETENTION_DAYS: ${STARRY_STATS_USAGE_EVENT_RETENTION_DAYS:-400}
      STARRY_STATS_USAGE_EVENT_DELETE_BATCH_SIZE: ${STARRY_STATS_USAGE_EVENT_DELETE_BATCH_SIZE:-10000}
      STARRY_STATS_USAGE_EVENT_DELETE_MAX_BATCHES: ${STARRY_STATS_USAGE_EVENT_DELETE_MAX_BATCHES:-100}
      STARRY_ACCOUNT_ALLOCATION_MAINTENANCE_ENABLED: ${STARRY_ACCOUNT_ALLOCATION_MAINTENANCE_ENABLED:-true}
      STARRY_ACCOUNT_ALLOCATION_MAINTENANCE_CRON: "${STARRY_ACCOUNT_ALLOCATION_MAINTENANCE_CRON:-0 35 * * * *}"
      STARRY_ACCOUNT_ALLOCATION_LEASE_RETENTION_DAYS: ${STARRY_ACCOUNT_ALLOCATION_LEASE_RETENTION_DAYS:-7}
      STARRY_ACCOUNT_ALLOCATION_EVENT_RETENTION_DAYS: ${STARRY_ACCOUNT_ALLOCATION_EVENT_RETENTION_DAYS:-90}
      STARRY_ACCOUNT_ALLOCATION_DELETE_BATCH_SIZE: ${STARRY_ACCOUNT_ALLOCATION_DELETE_BATCH_SIZE:-10000}
      STARRY_ACCOUNT_ALLOCATION_DELETE_MAX_BATCHES: ${STARRY_ACCOUNT_ALLOCATION_DELETE_MAX_BATCHES:-20}
      # Optional Mihomo: deployed by the "mihomo" profile together with the controller secret, and switched on by an
      # administrator in the console (see docs/ACCOUNT_EGRESS.md). STARRY_EGRESS_MIHOMO_ENABLED is only the initial
      # state of that switch for setups that never used it; every other STARRY_EGRESS_* setting keeps its default.
      STARRY_EGRESS_MIHOMO_ENABLED: ${STARRY_EGRESS_MIHOMO_ENABLED:-false}
      STARRY_EGRESS_CONTROLLER_SECRET: ${STARRY_EGRESS_CONTROLLER_SECRET:-}
      # glibc keeps one malloc arena per thread by default, which inflates resident memory of a busy JVM.
      MALLOC_ARENA_MAX: "2"
      JAVA_OPTS: "${JAVA_OPTS:--Xms64m -Xmx768m -XX:MaxDirectMemorySize=256m -XX:+ExitOnOutOfMemoryError -XX:G1PeriodicGCInterval=300000 -XX:-G1PeriodicGCInvokesConcurrent -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30}"
    ports:
      - "${HOST_BIND_ADDR:-127.0.0.1}:${HOST_HTTP_PORT:-18080}:${SERVER_PORT:-8080}"
    command: ["sh", "-c", "exec java $${JAVA_OPTS:-} -jar /app/app.jar"]
    networks:
      - starryapi

  # Optional Mihomo (Clash.Meta): only Clash subscriptions and protocols the Java backend cannot speak need it. The
  # installer deploys it idle (COMPOSE_PROFILES=mihomo and STARRY_EGRESS_CONTROLLER_SECRET in .env) and an administrator
  # switches it on in the console (docs/ACCOUNT_EGRESS.md). The official image is used as is; Starry API never bundles
  # the binary. It shares the service network namespace, so the controller (9090) and the per-node listeners stay on
  # 127.0.0.1 and nothing is published.
  mihomo:
    profiles: ["mihomo"]
    image: metacubex/mihomo:${MIHOMO_VERSION:-v1.19.32}
    network_mode: "service:service"
    restart: unless-stopped
    entrypoint: ["/bin/sh", "/entrypoint.sh"]
    environment:
      # Compose interpolates inactive profiles too, so this must not be required with ":?"; the entrypoint refuses an
      # empty secret instead.
      STARRY_EGRESS_CONTROLLER_SECRET: ${STARRY_EGRESS_CONTROLLER_SECRET:-}
      GOMEMLIMIT: ${MIHOMO_GOMEMLIMIT:-200MiB}
    volumes:
      - ./docker/egress/mihomo-entrypoint.sh:/entrypoint.sh:ro
    read_only: true
    tmpfs:
      - /tmp:size=64m
    cap_drop: [ALL]
    security_opt: [no-new-privileges:true]
    mem_limit: ${MIHOMO_MEM_LIMIT:-256m}

networks:
  starryapi:
    name: starryapi
EOF
}

download_artifact() {
  local tmp_dir tmp_jar tmp_sha tmp_info tmp_recovery tmp_recovery_sha actual
  tmp_dir="$(mktemp -d)"
  tmp_jar="${tmp_dir}/starry-api.jar"
  tmp_sha="${tmp_dir}/starry-api.jar.sha256"
  tmp_info="${tmp_dir}/build-info.json"
  tmp_recovery="${tmp_dir}/recover-data-management.sh"
  tmp_recovery_sha="${tmp_dir}/recover-data-management.sh.sha256"

  download_required_file "artifact jar" "$ARTIFACT_URL" "$tmp_jar"
  download_required_file "artifact checksum" "$CHECKSUM_URL" "$tmp_sha"
  download_required_file "data-management recovery tool" "$RECOVERY_TOOL_URL" "$tmp_recovery"
  download_required_file "data-management recovery tool checksum" "$RECOVERY_TOOL_CHECKSUM_URL" "$tmp_recovery_sha"
  download_file "$BUILD_INFO_URL" "$tmp_info" || true

  install_verified_recovery_tool "$tmp_recovery" "$tmp_recovery_sha"
  install_verified_artifact "$tmp_jar" "$tmp_sha"
  actual="$INSTALLED_ARTIFACT_CHECKSUM"
  if [[ -s "$tmp_info" ]]; then
    atomic_install_file "$tmp_info" "${INSTALL_DIR}/app/build-info.json" 0644 \
      || fail "Could not install build metadata atomically."
  fi
  rm -rf "$tmp_dir"
  log "Installed artifact with SHA256 ${actual}"
}

validate_bundle_archive() {
  local archive="$1"
  local entry normalized listing entry_type

  LC_ALL=C tar -Ptzf "$archive" >/dev/null \
    || fail "Deployment bundle is not a readable gzip-compressed tar archive."

  while IFS= read -r entry; do
    case "$entry" in
      /*)
        fail "Deployment bundle contains an absolute path: ${entry}"
        ;;
    esac

    normalized="$entry"
    while [[ "$normalized" == ./* ]]; do
      normalized="${normalized#./}"
    done
    case "/${normalized}/" in
      */../*)
        fail "Deployment bundle contains a parent-directory path: ${entry}"
        ;;
    esac
  done < <(LC_ALL=C tar -Ptzf "$archive")

  while IFS= read -r listing; do
    [[ -n "$listing" ]] || continue
    entry_type="${listing:0:1}"
    case "$entry_type" in
      -|d) ;;
      *)
        fail "Deployment bundle contains a link or special file; only regular files and directories are allowed."
        ;;
    esac
  done < <(LC_ALL=C tar -Ptvzf "$archive")
}

download_bundle() {
  local tmp_dir tmp_bundle tmp_sha extract_dir expected actual artifact_actual recovery_expected recovery_actual
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

  validate_bundle_archive "$tmp_bundle"
  tar --no-same-owner --no-same-permissions -xzf "$tmp_bundle" -C "$extract_dir"
  [[ -f "${extract_dir}/app/starry-api.jar" ]] || fail "Bundle does not contain app/starry-api.jar."
  [[ -f "${extract_dir}/app/starry-api.jar.sha256" ]] || fail "Bundle does not contain the jar checksum."
  [[ -f "${extract_dir}/docker-compose.artifact.yml" ]] || fail "Bundle does not contain docker-compose.artifact.yml."
  [[ -f "${extract_dir}/postgres-init/001-starry-api-roles.sh" ]] \
    || fail "Bundle does not contain the PostgreSQL role bootstrap script."
  [[ -f "${extract_dir}/runtime/recover-data-management.sh" ]] \
    || fail "Bundle does not contain the data-management recovery tool."
  [[ -f "${extract_dir}/runtime/recover-data-management.sh.sha256" ]] \
    || fail "Bundle does not contain the recovery tool checksum."

  expected="$(awk '{print $1}' "${extract_dir}/app/starry-api.jar.sha256" | head -n 1)"
  artifact_actual="$(sha256_file "${extract_dir}/app/starry-api.jar")"
  [[ -n "$expected" && "$expected" == "$artifact_actual" ]] \
    || fail "Bundle jar checksum mismatch. expected=${expected:-missing} actual=${artifact_actual}"
  recovery_expected="$(awk '{print $1}' "${extract_dir}/runtime/recover-data-management.sh.sha256" | head -n 1)"
  recovery_actual="$(sha256_file "${extract_dir}/runtime/recover-data-management.sh")"
  [[ -n "$recovery_expected" && "$recovery_expected" == "$recovery_actual" ]] \
    || fail "Bundle recovery tool checksum mismatch. expected=${recovery_expected:-missing} actual=${recovery_actual}"

  atomic_install_file "${extract_dir}/docker-compose.artifact.yml" "${INSTALL_DIR}/docker-compose.artifact.yml" 0644 \
    || fail "Could not install Docker Compose configuration atomically."
  atomic_install_file "${extract_dir}/postgres-init/001-starry-api-roles.sh" "${INSTALL_DIR}/postgres-init/001-starry-api-roles.sh" 0755 \
    || fail "Could not install the PostgreSQL bootstrap atomically."
  if [[ -f "${extract_dir}/docker/egress/mihomo-entrypoint.sh" ]]; then
    install_mihomo_entrypoint "${extract_dir}/docker/egress/mihomo-entrypoint.sh"
  fi
  [[ ! -f "${extract_dir}/build-info.json" ]] \
    || atomic_install_file "${extract_dir}/build-info.json" "${INSTALL_DIR}/app/build-info.json" 0644 \
    || fail "Could not install build metadata atomically."
  [[ ! -f "${extract_dir}/sha256sums.txt" ]] \
    || atomic_install_file "${extract_dir}/sha256sums.txt" "${INSTALL_DIR}/app/sha256sums.txt" 0644 \
    || fail "Could not install the bundle checksum manifest atomically."
  [[ ! -f "${extract_dir}/starry-api-webui.tar.gz" ]] \
    || atomic_install_file "${extract_dir}/starry-api-webui.tar.gz" "${INSTALL_DIR}/app/starry-api-webui.tar.gz" 0644 \
    || fail "Could not install the WebUI archive atomically."
  [[ ! -f "${extract_dir}/starry-api-webui.tar.gz.sha256" ]] \
    || atomic_install_file "${extract_dir}/starry-api-webui.tar.gz.sha256" "${INSTALL_DIR}/app/starry-api-webui.tar.gz.sha256" 0644 \
    || fail "Could not install the WebUI checksum atomically."
  if [[ -f "${extract_dir}/starry-api.sh" && -f "${extract_dir}/starry-api.sh.sha256" ]]; then
    install_verified_cli_tool "${extract_dir}/starry-api.sh" "${extract_dir}/starry-api.sh.sha256"
  fi
  install_verified_recovery_tool \
    "${extract_dir}/runtime/recover-data-management.sh" \
    "${extract_dir}/runtime/recover-data-management.sh.sha256"
  install_verified_artifact \
    "${extract_dir}/app/starry-api.jar" \
    "${extract_dir}/app/starry-api.jar.sha256" >/dev/null

  rm -rf "$tmp_dir"
  log "Installed deployment bundle with SHA256 ${actual}"
}

ensure_postgres_init_script() {
  local target="${INSTALL_DIR}/postgres-init/001-starry-api-roles.sh"
  if [[ ! -f "$target" ]]; then
    local temporary
    temporary="$(mktemp "${target}.download.XXXXXX")" \
      || fail "Could not prepare the PostgreSQL bootstrap download."
    download_required_file "PostgreSQL role bootstrap" "$POSTGRES_INIT_URL" "$temporary"
    atomic_install_file "$temporary" "$target" 0755 \
      || fail "Could not install the PostgreSQL bootstrap atomically."
    rm -f -- "$temporary"
  fi
  chmod 0755 "$target"
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

report_stack_failure() {
  root_cmd "${COMPOSE_CMD[@]}" -f docker-compose.artifact.yml ps || true
  root_cmd "${COMPOSE_CMD[@]}" -f docker-compose.artifact.yml logs --tail=120 postgres || true
  root_cmd "${COMPOSE_CMD[@]}" -f docker-compose.artifact.yml logs --tail=160 service || true
  if mihomo_profile_listed "${INSTALL_DIR}/.env"; then
    root_cmd "${COMPOSE_CMD[@]}" -f docker-compose.artifact.yml logs --tail=40 mihomo || true
  fi
}

try_artifact_rollback() {
  ROLLBACK_ATTEMPTED=true
  rollback_installed_artifact || return 1
  log "Recreating the stack with the previous deployment unit..."
  root_cmd "${COMPOSE_CMD[@]}" -f docker-compose.artifact.yml up -d --build --force-recreate --remove-orphans \
    || return 1
  discard_unwanted_mihomo
  wait_for_health || return 1
  ROLLBACK_COMPLETED=true
}

# The deployment summary's lines for the administration command (each starts or ends with its own newline so that
# nothing is printed when this release does not have one).
cli_summary_line() {
  [[ -n "$CLI_COMMAND" ]] || return 0
  printf '\nRoot login info:    %s info   (console address, initial Root password while unchanged)' "$CLI_COMMAND"
}

cli_common_commands() {
  [[ -n "$CLI_COMMAND" ]] || return 0
  printf '  %s info             # console address, Root account, password state\n' "$CLI_COMMAND"
  printf '  %s reset-password   # set a new temporary Root password (restarts the service)\n' "$CLI_COMMAND"
}

deploy_stack() {
  cd "$INSTALL_DIR"
  # Mihomo shares the service's network namespace, so it must be recreated together with the service: a container left
  # attached to the old one would keep running without being reachable. Naming services on the command line does not
  # start a profile's service on its own, so it is listed explicitly when the profile is selected in .env.
  local -a stack_services=(service)
  if mihomo_profile_listed "${INSTALL_DIR}/.env"; then
    stack_services+=(mihomo)
    MIHOMO_STACK_STARTED=true
  fi
  log "Starting Docker Compose stack from prebuilt artifact..."
  STACK_MUTATION_STARTED=true
  root_cmd "${COMPOSE_CMD[@]}" -f docker-compose.artifact.yml pull postgres || true
  root_cmd "${COMPOSE_CMD[@]}" -f docker-compose.artifact.yml up -d postgres
  log "Recreating ${stack_services[*]} so the new jar is loaded..."
  if ! root_cmd "${COMPOSE_CMD[@]}" -f docker-compose.artifact.yml up -d --build --force-recreate "${stack_services[@]}"; then
    report_stack_failure
    if try_artifact_rollback; then
      fail "The new service could not be started; the previous deployment unit was restored and is healthy."
    fi
    report_stack_failure
    fail "The new service could not be started and automatic artifact rollback failed."
  fi

  if ! wait_for_health; then
    report_stack_failure
    if try_artifact_rollback; then
      fail "The new service did not become healthy; the previous deployment unit was restored and is healthy."
    fi
    report_stack_failure
    fail "Service did not become healthy and automatic artifact rollback failed. Inspect the logs above."
  fi

  log "Removing containers left behind by the previous Compose definition..."
  root_cmd "${COMPOSE_CMD[@]}" -f docker-compose.artifact.yml up -d --remove-orphans postgres "${stack_services[@]}"
  rm -f -- "${INSTALL_DIR}/.env.postgresql-retry"
  root_cmd "${COMPOSE_CMD[@]}" -f docker-compose.artifact.yml logs --tail=120 service || true

  local summary_cli_line summary_cli_commands
  summary_cli_line="$(cli_summary_line)"
  summary_cli_commands="$(cli_common_commands)"
  [[ -z "$summary_cli_commands" ]] || summary_cli_commands+=$'\n'

  cat <<EOF

Deployment complete.

Install dir:        ${INSTALL_DIR}
Artifact file:      ${INSTALL_DIR}/app/starry-api.jar
Local WebUI:        http://$(local_access_host):${HOST_HTTP_PORT}/#/login
Local API docs:     http://$(local_access_host):${HOST_HTTP_PORT}/docs
Reverse proxy to:   $(reverse_proxy_target)
Mihomo egress:      $(mihomo_state_line)${summary_cli_line}

Common commands:
${summary_cli_commands}  cd ${INSTALL_DIR}
  docker compose -f docker-compose.artifact.yml logs -f service
  docker compose -f docker-compose.artifact.yml restart service
  docker compose -f docker-compose.artifact.yml down
EOF
}

main() {
  require_linux
  require_root
  validate_enable_mihomo
  install_base_packages
  ensure_docker
  ensure_compose
  require_compose_inline_dockerfile
  prepare_install_dir
  load_github_token
  backup_current_artifact
  trap 'deployment_exit_handler $?' EXIT
  write_env_file
  load_runtime_env
  write_compose_file
  download_bundle || download_artifact
  ensure_postgres_init_script
  ensure_mihomo_support
  ensure_cli_tool
  deploy_stack
  DEPLOYMENT_COMMITTED=true
}

if [[ "${STARRY_INSTALL_SERVER_LIBRARY_ONLY:-false}" != "true" ]]; then
  main "$@"
fi
