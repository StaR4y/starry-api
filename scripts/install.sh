#!/usr/bin/env bash
# One-click installer and upgrader for Starry API on a Linux server. It installs Docker when it is missing, generates the
# secrets, starts the published image with PostgreSQL, waits until the service is healthy, and puts the "starry-api"
# administration command on the PATH.
#
#   curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install.sh | sudo bash
#
# Run it again to upgrade: .env and data/ stay as they are, and a failed upgrade restores the previous version. A server
# installed from the older jar bundle (docker-compose.artifact.yml) is taken over in place with the same .env and data/.
#
# Settings, all optional (full list in docs/DEPLOYMENT.md):
#   STARRY_VERSION   release tag to install, for example v1.2.3; default: the latest release
#   INSTALL_DIR      default /opt/starry-api
#   HOST_BIND_ADDR   default 0.0.0.0; HOST_HTTP_PORT default 18080 (both only used on a fresh install)
#   PUBLIC_BASE_URL  the address users reach the console at, when it is behind a reverse proxy
#   ENABLE_MIHOMO    auto (default), true or false, see below
#   IMAGE_REPO       registry mirror, for example registry.example.com/starry-api; the tag stays the release tag
#   STARRY_IMAGE     exact image reference to deploy instead
#   PULL_IMAGE       false to use an image already on this machine, for example from "docker load"; default true
#   REPO_RAW_BASE    mirror of the repository files (raw) at that release
set -euo pipefail

APP_NAME="starry-api"
GITHUB_REPO="${GITHUB_REPO:-StaR4y/starry-api}"
DEFAULT_IMAGE_REPO="ghcr.io/star4y/starry-api"
STARRY_VERSION="${STARRY_VERSION:-latest}"
IMAGE_REPO="${IMAGE_REPO:-}"
# Compose gives a variable of the calling shell priority over .env, and a rollback must get the image named by the restored
# .env, so an image requested for this run is kept under another name.
REQUESTED_IMAGE="${STARRY_IMAGE:-}"
unset STARRY_IMAGE
PULL_IMAGE="${PULL_IMAGE:-true}"
REPO_RAW_BASE="${REPO_RAW_BASE:-}"
INSTALL_DIR="${INSTALL_DIR:-/opt/starry-api}"
COMPOSE_FILE_NAME="docker-compose.yml"
# The jar-based deployment this installer replaces: its Compose file marks a server that has not been taken over yet.
LEGACY_COMPOSE_FILE="docker-compose.artifact.yml"
# What the installer downloads into the install directory, at the same paths as in the repository.
DEPLOYMENT_FILES=(
  "$COMPOSE_FILE_NAME"
  docker/postgresql/init/001-starry-api-roles.sh
  docker/egress/mihomo-entrypoint.sh
  # The opt-in TLS bridge overlay is built on the server, so it needs its sources (docs/ACCOUNT_EGRESS.md).
  docker-compose.tls-bridge.yml
  docker/egress/Dockerfile
  docker/egress/bridge.py
  docker/egress/requirements.txt
)
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
#         container silently.
#   true  also adds it to an existing .env (keeping the old file as .env.previous).
#   false never adds it. Anything already in .env stays; remove COMPOSE_PROFILES and STARRY_EGRESS_CONTROLLER_SECRET
#         from .env and run "docker compose rm -sf mihomo" to remove it.
ENABLE_MIHOMO="${ENABLE_MIHOMO:-auto}"
# Said whenever Mihomo is deployed: its license is not the project's, and the image is not ours to distribute.
MIHOMO_LICENSE_NOTE="the image metacubex/mihomo (https://github.com/MetaCubeX/mihomo) is licensed under GPL-3.0; Docker pulls it from its registry as a separate container, and Starry API does not bundle or distribute it"
RELEASE_TAG=""
DEPLOY_IMAGE=""
# The Compose file of the deployment that was here when this run started; empty on a fresh install.
PREVIOUS_COMPOSE_FILE=""
# The Compose file the stack commands use: the new one, and the previous one once a rollback has restored it.
ACTIVE_COMPOSE_FILE="$COMPOSE_FILE_NAME"
ROLLBACK_AVAILABLE=false
ENV_ROLLBACK_AVAILABLE=false
ENV_UPDATED=false
MIHOMO_STACK_STARTED=false
STACK_MUTATION_STARTED=false
DEPLOYMENT_COMMITTED=false
ROLLBACK_COMPLETED=false
ROLLBACK_ATTEMPTED=false
# CLI_COMMAND is how the deployment summary names the administration command.
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

ensure_base_packages() {
  local pm
  if command -v curl >/dev/null 2>&1 && command -v openssl >/dev/null 2>&1; then
    return 0
  fi
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

require_compose_v2() {
  local version major
  version="$(root_cmd "${COMPOSE_CMD[@]}" version --short 2>/dev/null || true)"
  version="${version#v}"
  major="${version%%.*}"
  if [[ ! "${major:-}" =~ ^[0-9]+$ ]] || (( major < 2 )); then
    fail "Docker Compose 2 or newer is required. Found: ${version:-unknown}"
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

deployment_file_mode() {
  case "$1" in
    docker/postgresql/init/*) printf '0755' ;;
    *) printf '0644' ;;
  esac
}

# Keeps what the installer is about to replace. The jar-based deployment needs no copies of its own files, because it uses
# other paths and nothing touches them before the new deployment is healthy.
backup_current_deployment() {
  local file source

  if [[ -f "${INSTALL_DIR}/${LEGACY_COMPOSE_FILE}" ]]; then
    PREVIOUS_COMPOSE_FILE="$LEGACY_COMPOSE_FILE"
  elif [[ -f "${INSTALL_DIR}/${COMPOSE_FILE_NAME}" ]]; then
    PREVIOUS_COMPOSE_FILE="$COMPOSE_FILE_NAME"
  else
    return 0
  fi

  for file in "${DEPLOYMENT_FILES[@]}"; do
    source="${INSTALL_DIR}/${file}"
    if [[ -f "$source" ]]; then
      atomic_install_file "$source" "${source}.previous" "$(deployment_file_mode "$file")" \
        || fail "Could not preserve ${file} for rollback."
    else
      rm -f -- "${source}.previous"
    fi
  done
  if [[ -f "${INSTALL_DIR}/.env" ]]; then
    atomic_install_file "${INSTALL_DIR}/.env" "${INSTALL_DIR}/.env.previous" 0600 \
      || fail "Could not preserve the current environment file for rollback."
    ENV_ROLLBACK_AVAILABLE=true
  else
    rm -f -- "${INSTALL_DIR}/.env.previous"
  fi
  ROLLBACK_AVAILABLE=true
}

# The administration command is a convenience for operators, so a release that does not offer it (or a download that
# fails) only skips it with a notice; an earlier copy stays.
download_and_install_cli_tool() {
  local tmp_dir tmp_script

  tmp_dir="$(mktemp -d)"
  tmp_script="${tmp_dir}/starry-api.sh"
  log "Downloading the administration command: ${REPO_RAW_BASE%/}/scripts/starry-api.sh"
  if download_file "${REPO_RAW_BASE%/}/scripts/starry-api.sh" "$tmp_script" && [[ -s "$tmp_script" ]]; then
    atomic_install_file "$tmp_script" "${INSTALL_DIR}/starry-api" 0755 \
      || { rm -rf "$tmp_dir"; fail "Could not install the administration command atomically."; }
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
  download_and_install_cli_tool
  link_cli_tool
}

restore_previous_deployment() {
  local file target
  local previous_env="${INSTALL_DIR}/.env.previous"
  local current_env="${INSTALL_DIR}/.env"
  local retry_env="${INSTALL_DIR}/.env.postgresql-retry"

  [[ "$ROLLBACK_AVAILABLE" == "true" ]] || return 1

  for file in "${DEPLOYMENT_FILES[@]}"; do
    target="${INSTALL_DIR}/${file}"
    if [[ -f "${target}.previous" ]]; then
      atomic_install_file "${target}.previous" "$target" "$(deployment_file_mode "$file")" || return 1
    else
      rm -f -- "$target" || return 1
      rmdir -p -- "$(dirname "$target")" 2>/dev/null || true
    fi
  done
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
  ENV_UPDATED=false
  ACTIVE_COMPOSE_FILE="$PREVIOUS_COMPOSE_FILE"
  load_runtime_env
  log "Restored the previous environment and deployment files after the failed deployment."
}

# The old jar deployment builds its runtime image from the Compose file; a published image is already on this machine.
recreate_previous_stack() {
  local build_flag="--no-build"
  [[ "$PREVIOUS_COMPOSE_FILE" != "$LEGACY_COMPOSE_FILE" ]] || build_flag="--build"
  root_cmd "${COMPOSE_CMD[@]}" -f "$PREVIOUS_COMPOSE_FILE" up -d "$build_flag" --force-recreate --remove-orphans
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
  if [[ "$ROLLBACK_AVAILABLE" != "true" ]]; then
    log "Nothing to roll back to on a fresh install. Fix the problem and run the installer again; it reuses ${INSTALL_DIR}/.env."
    exit "$status"
  fi

  ROLLBACK_ATTEMPTED=true
  log "Deployment failed before commit; restoring the previous deployment..."
  if ! restore_previous_deployment; then
    log "Automatic deployment-file rollback failed. Inspect ${INSTALL_DIR} before retrying."
    exit "$status"
  fi
  if [[ "$STACK_MUTATION_STARTED" == "true" ]]; then
    cd "$INSTALL_DIR" || exit "$status"
    if recreate_previous_stack \
      && discard_unwanted_mihomo \
      && wait_for_health; then
      ROLLBACK_COMPLETED=true
      log "The previous deployment is healthy after automatic rollback."
    else
      log "The previous deployment files were restored, but the old stack did not recover automatically."
    fi
  fi
  exit "$status"
}

download_file() {
  curl -fsSL "$1" -o "$2"
}

# Follows the redirect of /releases/latest, which has no API rate limit: it ends at .../releases/tag/<tag> when a release
# exists and at the plain releases page when none does.
latest_release_tag() {
  local url
  url="$(curl -fsSLI -o /dev/null -w '%{url_effective}' "https://github.com/${GITHUB_REPO}/releases/latest" 2>/dev/null)" || return 1
  case "$url" in
    */releases/tag/*) printf '%s' "${url##*/}" ;;
    *) return 1 ;;
  esac
}

# registry/name[:tag][@digest] without the tag and the digest; a colon before the last slash is a registry port.
image_repository() {
  local image="${1%%@*}"
  if [[ "${image##*/}" == *:* ]]; then
    image="${image%:*}"
  fi
  printf '%s' "$image"
}

# Decides the release and the image before anything changes. An exact STARRY_IMAGE wins. Otherwise only the tag moves:
# the repository stays what .env names, so a registry mirror survives an upgrade, unless IMAGE_REPO names another.
resolve_release() {
  if [[ "$STARRY_VERSION" == "latest" ]]; then
    log "Looking up the latest release of ${GITHUB_REPO}..."
    RELEASE_TAG="$(latest_release_tag)" \
      || fail "Could not find a published release of ${GITHUB_REPO}. Name one with STARRY_VERSION=v1.2.3, or build from source (docs/DEPLOYMENT.md)."
  else
    RELEASE_TAG="$STARRY_VERSION"
  fi
  [[ "$RELEASE_TAG" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || fail "STARRY_VERSION is not a release tag: ${RELEASE_TAG}"
  REPO_RAW_BASE="${REPO_RAW_BASE:-https://raw.githubusercontent.com/${GITHUB_REPO}/${RELEASE_TAG}}"

  if [[ -n "$REQUESTED_IMAGE" ]]; then
    DEPLOY_IMAGE="$REQUESTED_IMAGE"
  else
    local repository="$IMAGE_REPO"
    if [[ -z "$repository" && -f "${INSTALL_DIR}/.env" ]]; then
      repository="$(image_repository "$(env_file_plain_value "${INSTALL_DIR}/.env" STARRY_IMAGE)")"
    fi
    DEPLOY_IMAGE="${repository:-$DEFAULT_IMAGE_REPO}:${RELEASE_TAG}"
  fi
  [[ "$DEPLOY_IMAGE" =~ ^[A-Za-z0-9][A-Za-z0-9._:/@-]*$ ]] || fail "Not an image reference: ${DEPLOY_IMAGE}"
  log "Installing ${RELEASE_TAG} with the image ${DEPLOY_IMAGE}."
}

set_image_in_env() {
  local env_file="${INSTALL_DIR}/.env"
  [[ "$(env_file_plain_value "$env_file" STARRY_IMAGE)" != "$DEPLOY_IMAGE" ]] || return 0
  set_env_value "$env_file" STARRY_IMAGE "$DEPLOY_IMAGE" || fail "Could not write STARRY_IMAGE to .env."
}

# Docker creates a directory for a bind mount whose source is missing, so one may be sitting where a file belongs.
install_deployment_file() {
  local relative="$1"
  local target="${INSTALL_DIR}/${relative}"
  local temporary

  if [[ -d "$target" ]]; then
    rmdir -- "$target" 2>/dev/null || fail "${target} is a directory; remove it so the file can be installed."
  fi
  mkdir -p "$(dirname "$target")" || fail "Could not create $(dirname "$target")."
  temporary="$(mktemp "${target}.download.XXXXXX")" || fail "Could not prepare the download of ${relative}."
  log "Downloading ${relative}: ${REPO_RAW_BASE%/}/${relative}"
  if ! download_file "${REPO_RAW_BASE%/}/${relative}" "$temporary" || [[ ! -s "$temporary" ]]; then
    rm -f -- "$temporary"
    fail "Could not download ${relative}. Check the network, or point REPO_RAW_BASE at a mirror of the repository."
  fi
  if ! atomic_install_file "$temporary" "$target" "$(deployment_file_mode "$relative")"; then
    rm -f -- "$temporary"
    fail "Could not install ${relative} atomically."
  fi
  rm -f -- "$temporary"
}

install_deployment_files() {
  local file
  for file in "${DEPLOYMENT_FILES[@]}"; do
    install_deployment_file "$file"
  done
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

# Where a browser finds the service, for the deployment summary: the public address, the server's own address when the
# port is published on every interface, or the address it is bound to.
reachable_base_url() {
  if [[ -n "$PUBLIC_BASE_URL" ]]; then
    printf '%s' "${PUBLIC_BASE_URL%/}"
  elif [[ "$HOST_BIND_ADDR" == "0.0.0.0" || "$HOST_BIND_ADDR" == "::" ]]; then
    printf 'http://%s:%s' "$(detect_host_ip)" "$HOST_HTTP_PORT"
  else
    printf 'http://%s:%s' "$HOST_BIND_ADDR" "$HOST_HTTP_PORT"
  fi
}

console_url() {
  printf '%s/#/login' "$(reachable_base_url)"
}

api_docs_url() {
  printf '%s/docs' "$(reachable_base_url)"
}

prepare_install_dir() {
  [[ ! -f "${INSTALL_DIR}/settings.gradle" ]] \
    || fail "${INSTALL_DIR} is a source checkout; use another INSTALL_DIR, or run docker compose up -d --build there."
  log "Preparing ${INSTALL_DIR}..."
  root_cmd mkdir -p \
    "$INSTALL_DIR/data/postgresql" \
    "$INSTALL_DIR/data/backups" \
    "$INSTALL_DIR/data/security" \
    "$INSTALL_DIR/data/plugins" \
    "$INSTALL_DIR/data/documentation" \
    "$INSTALL_DIR/data/model-ranking" \
    "$INSTALL_DIR/data/model-pricing"
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

# Compose does not count a container of an inactive profile as an orphan, so a Mihomo container started by a deployment
# that was rolled back (and whose .env no longer selects the profile) has to be removed explicitly.
discard_unwanted_mihomo() {
  [[ "$MIHOMO_STACK_STARTED" == "true" ]] || return 0
  if ! mihomo_profile_listed "${INSTALL_DIR}/.env"; then
    root_cmd "${COMPOSE_CMD[@]}" -f "$ACTIVE_COMPOSE_FILE" rm -sf mihomo || true
  fi
  return 0
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
# Written by the Starry API installer. docker-compose.yml holds the default of every other setting; .env.example in the
# repository lists them. Add a line here to change one, then run: docker compose up -d
HOST_BIND_ADDR=${HOST_BIND_ADDR}
HOST_HTTP_PORT=${HOST_HTTP_PORT}
SERVER_PORT=${SERVER_PORT}

POSTGRES_BOOTSTRAP_PASSWORD=${bootstrap_password}
POSTGRES_MIGRATION_PASSWORD=${migration_password}
POSTGRES_PASSWORD=${application_password}

STARRY_WEBUI_URL=$(webui_url)

# AGPL-3.0, section 13: where the users of this service can get its source code (the console footer links to it).
# If you run a modified version, set this to where your modified source can be downloaded.
STARRY_SOURCE_URL=https://github.com/StaR4y/starry-api
EOF
  if [[ "$ENABLE_MIHOMO" != "false" ]]; then
    if [[ "$ENABLE_MIHOMO" == "true" ]]; then
      log "Deploying the optional Mihomo component (ENABLE_MIHOMO=true)."
    else
      log "Deploying the optional Mihomo component (ENABLE_MIHOMO=auto); an administrator switches it on in the console."
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
  root_cmd "${COMPOSE_CMD[@]}" -f "$ACTIVE_COMPOSE_FILE" ps || true
  root_cmd "${COMPOSE_CMD[@]}" -f "$ACTIVE_COMPOSE_FILE" logs --tail=120 postgres || true
  root_cmd "${COMPOSE_CMD[@]}" -f "$ACTIVE_COMPOSE_FILE" logs --tail=160 service || true
  if mihomo_profile_listed "${INSTALL_DIR}/.env"; then
    root_cmd "${COMPOSE_CMD[@]}" -f "$ACTIVE_COMPOSE_FILE" logs --tail=40 mihomo || true
  fi
}

try_rollback() {
  [[ "$ROLLBACK_AVAILABLE" == "true" ]] || return 1
  ROLLBACK_ATTEMPTED=true
  restore_previous_deployment || return 1
  log "Recreating the stack with the previous deployment..."
  recreate_previous_stack || return 1
  discard_unwanted_mihomo
  wait_for_health || return 1
  ROLLBACK_COMPLETED=true
}

# Stops the deployment after a stack failure: restores the previous version when there is one, and says what happened.
fail_stack() {
  local problem="$1"
  report_stack_failure
  if try_rollback; then
    fail "${problem}; the previous deployment was restored and is healthy."
  fi
  if [[ "$ROLLBACK_ATTEMPTED" == "true" ]]; then
    report_stack_failure
    fail "${problem}, and the automatic rollback failed. Inspect the logs above."
  fi
  fail "${problem}. Inspect the logs above, fix the problem and run the installer again; it reuses ${INSTALL_DIR}/.env."
}

# The old jar deployment stays on disk until the new one is healthy. Moving it aside afterwards is what marks the server
# as taken over, for this installer and for the administration command. Nothing is deleted: the operator does that.
retire_legacy_deployment() {
  [[ "$PREVIOUS_COMPOSE_FILE" == "$LEGACY_COMPOSE_FILE" ]] || return 0
  local legacy_dir="${INSTALL_DIR}/legacy-jar-deployment.$(date +%Y%m%d%H%M%S)"
  local item
  mkdir -p "$legacy_dir" || { log "Could not create ${legacy_dir}; move ${LEGACY_COMPOSE_FILE} aside yourself."; return 0; }
  for item in "$LEGACY_COMPOSE_FILE" "${LEGACY_COMPOSE_FILE}.previous" app postgres-init runtime; do
    if [[ -e "${INSTALL_DIR}/${item}" ]]; then
      mv -- "${INSTALL_DIR}/${item}" "$legacy_dir/" || log "Could not move ${item} to ${legacy_dir}."
    fi
  done
  log "The previous jar deployment now runs from the image. Its files are in ${legacy_dir}; delete them once you are satisfied."
}

# The deployment summary's line about the Mihomo license (starts with its own newline, and is empty when Mihomo is not deployed).
mihomo_license_summary_line() {
  mihomo_profile_listed "${INSTALL_DIR}/.env" || return 0
  printf '\nMihomo license:     %s' "${MIHOMO_LICENSE_NOTE}"
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
    log "Mihomo is deployed: ${MIHOMO_LICENSE_NOTE}."
  fi
  local -a compose=("${COMPOSE_CMD[@]}" -f "$COMPOSE_FILE_NAME")

  # Everything that can fail without touching the running stack comes first. Pulling the image directly, because Compose
  # treats a failed pull of a service with a build section as a warning.
  root_cmd "${compose[@]}" config -q || fail "The downloaded ${COMPOSE_FILE_NAME} does not work with this .env."
  if [[ "$PULL_IMAGE" == "false" ]]; then
    root_cmd docker image inspect "$DEPLOY_IMAGE" >/dev/null \
      || fail "PULL_IMAGE=false, but the image ${DEPLOY_IMAGE} is not on this machine."
  else
    log "Pulling ${DEPLOY_IMAGE}..."
    root_cmd docker pull "$DEPLOY_IMAGE" \
      || fail "Could not pull ${DEPLOY_IMAGE}. Check the network, or name a mirror with IMAGE_REPO or STARRY_IMAGE."
  fi
  root_cmd "${compose[@]}" pull postgres || true

  log "Starting the Docker Compose stack..."
  STACK_MUTATION_STARTED=true
  root_cmd "${compose[@]}" up -d postgres
  log "Recreating ${stack_services[*]} from the image..."
  if ! root_cmd "${compose[@]}" up -d --no-build --force-recreate "${stack_services[@]}"; then
    fail_stack "The new service could not be started"
  fi
  if ! wait_for_health; then
    fail_stack "The new service did not become healthy"
  fi

  log "Removing containers left behind by the previous Compose definition..."
  root_cmd "${compose[@]}" up -d --no-build --remove-orphans postgres "${stack_services[@]}"
  rm -f -- "${INSTALL_DIR}/.env.postgresql-retry"
  retire_legacy_deployment
  root_cmd "${compose[@]}" logs --tail=120 service || true

  local summary_cli_line summary_cli_commands summary_mihomo_license
  summary_cli_line="$(cli_summary_line)"
  summary_mihomo_license="$(mihomo_license_summary_line)"
  summary_cli_commands="$(cli_common_commands)"
  [[ -z "$summary_cli_commands" ]] || summary_cli_commands+=$'\n'

  cat <<EOF

Deployment complete.

Install dir:        ${INSTALL_DIR}
Version:            ${RELEASE_TAG:-unknown} (${DEPLOY_IMAGE:-image from .env})
Console:            $(console_url)
API docs:           $(api_docs_url)
Reverse proxy to:   $(reverse_proxy_target)
Mihomo egress:      $(mihomo_state_line)${summary_mihomo_license}${summary_cli_line}

Upgrade by running the installer again; .env and data/ stay as they are.

Common commands:
${summary_cli_commands}  cd ${INSTALL_DIR}
  docker compose logs -f service
  docker compose restart service
  docker compose down
EOF
}

# Everything except the checks that only make sense on a real server.
install_flow() {
  validate_enable_mihomo
  ensure_base_packages
  ensure_docker
  ensure_compose
  require_compose_v2
  prepare_install_dir
  resolve_release
  backup_current_deployment
  trap 'deployment_exit_handler $?' EXIT
  write_env_file
  set_image_in_env
  load_runtime_env
  install_deployment_files
  ensure_cli_tool
  deploy_stack
  DEPLOYMENT_COMMITTED=true
}

main() {
  require_linux
  require_root
  install_flow
}

if [[ "${STARRY_INSTALL_LIBRARY_ONLY:-false}" != "true" ]]; then
  main "$@"
fi
