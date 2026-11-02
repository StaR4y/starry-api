#!/usr/bin/env bash
# Runs the real installer against a real Docker daemon: a fresh install, an upgrade that keeps the data, a failed upgrade
# that rolls back, and the administration command. CI runs it after building the image; it needs root on a Linux machine.
#
#   sudo scripts/test-install-e2e.sh IMAGE
#
# It uses INSTALL_DIR (default /opt/starry-api-e2e) and the containers named starryapi-*, and removes them afterwards, so it
# must not run where a real deployment lives.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${1:?Usage: test-install-e2e.sh IMAGE}"
BROKEN_IMAGE="starry-api:e2e-broken"
INSTALL_DIR="${E2E_INSTALL_DIR:-/opt/starry-api-e2e}"
HOST_HTTP_PORT="${E2E_HTTP_PORT:-28080}"
BASE_URL="http://127.0.0.1:${HOST_HTTP_PORT}"

log() {
  printf '[install-e2e] %s\n' "$*"
}

fail_test() {
  printf '[install-e2e] ERROR: %s\n' "$*" >&2
  exit 1
}

cleanup() {
  if [[ -f "${INSTALL_DIR}/docker-compose.yml" ]]; then
    (cd "$INSTALL_DIR" && docker compose down --volumes --remove-orphans >/dev/null 2>&1) || true
  fi
  rm -rf -- "$INSTALL_DIR"
  docker image rm "$BROKEN_IMAGE" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if docker ps -a --format '{{.Names}}' | grep -q '^starryapi-'; then
  fail_test "containers named starryapi-* already exist on this machine; this test would replace them."
fi
[[ ! -e "$INSTALL_DIR" ]] || fail_test "${INSTALL_DIR} already exists."

# The files of this checkout stand in for the release, and the image built by the caller for the published one.
run_installer() {
  env INSTALL_DIR="$INSTALL_DIR" \
    STARRY_VERSION=e2e \
    REPO_RAW_BASE="file://${ROOT_DIR}" \
    PULL_IMAGE=false \
    HOST_BIND_ADDR=127.0.0.1 \
    HOST_HTTP_PORT="$HOST_HTTP_PORT" \
    CLI_LINK_PATH="" \
    INSTALL_DOCKER=false \
    "$@" \
    bash "${ROOT_DIR}/scripts/install.sh"
}

in_install_dir() {
  (cd "$INSTALL_DIR" && "$@")
}

psql_query() {
  in_install_dir docker compose exec -T postgres psql -U starry_api_bootstrap -d starry_api -Atc "$1"
}

log "A fresh install starts from nothing and becomes healthy."
run_installer STARRY_IMAGE="$IMAGE"
curl -fsS "${BASE_URL}/health" >/dev/null || fail_test "the service must be healthy after a fresh install"
[[ "$(docker inspect --format '{{.Config.Image}}' starryapi-service)" == "$IMAGE" ]] \
  || fail_test "the service must run the requested image"
grep -q "^STARRY_IMAGE=${IMAGE}$" "${INSTALL_DIR}/.env" || fail_test ".env must name the image"
[[ "$(stat -c '%a' "${INSTALL_DIR}/.env")" == "600" ]] || fail_test ".env must be private"
grep -q 'logback' <<<"$(curl -fsS "${BASE_URL}/THIRD-PARTY-NOTICES.txt")" \
  || fail_test "the console must serve the third-party notices"
grep -qx mihomo <<<"$(in_install_dir docker compose ps --services --status running)" \
  || fail_test "a fresh install deploys Mihomo idle by default"

log "The console and the administration command work."
grep -qi '<html' <<<"$(curl -fsS "${BASE_URL}/")" || fail_test "the console must be served"
grep -q "$HOST_HTTP_PORT" <<<"$(INSTALL_DIR="$INSTALL_DIR" "${INSTALL_DIR}/starry-api" info)" \
  || fail_test "starry-api info must show the console address"

log "An upgrade keeps the passwords and the data."
psql_query "CREATE TABLE e2e_marker (value text); INSERT INTO e2e_marker VALUES ('kept');" >/dev/null
passwords_before="$(grep '^POSTGRES_' "${INSTALL_DIR}/.env")"
run_installer STARRY_IMAGE="$IMAGE"
curl -fsS "${BASE_URL}/health" >/dev/null || fail_test "the service must be healthy after an upgrade"
[[ "$(grep '^POSTGRES_' "${INSTALL_DIR}/.env")" == "$passwords_before" ]] || fail_test "an upgrade must keep the passwords"
[[ "$(psql_query 'SELECT value FROM e2e_marker')" == "kept" ]] || fail_test "an upgrade must keep the data"

log "A release that does not start is rolled back to the version that ran."
docker build -q -t "$BROKEN_IMAGE" - >/dev/null <<EOF
FROM ${IMAGE}
ENTRYPOINT ["sh", "-c", "exit 1"]
EOF
if run_installer STARRY_IMAGE="$BROKEN_IMAGE" HEALTH_WAIT_SECONDS=20; then
  fail_test "installing a broken image must fail"
fi
grep -q "^STARRY_IMAGE=${IMAGE}$" "${INSTALL_DIR}/.env" || fail_test "a rollback must restore the image in .env"
[[ "$(docker inspect --format '{{.Config.Image}}' starryapi-service)" == "$IMAGE" ]] \
  || fail_test "the previous image must be running after a rollback"
curl -fsS "${BASE_URL}/health" >/dev/null || fail_test "the previous version must be healthy after a rollback"
[[ "$(psql_query 'SELECT value FROM e2e_marker')" == "kept" ]] || fail_test "a rollback must keep the data"

log "All scenarios passed."
