#!/usr/bin/env bash
set -euo pipefail

APP_NAME="starry-api"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_DIR="${ARTIFACT_DIR:-artifacts}"
ARTIFACT_NAME="${ARTIFACT_NAME:-starry-api.jar}"
RUN_TESTS="${RUN_TESTS:-true}"
COMMIT_ARTIFACTS="${COMMIT_ARTIFACTS:-false}"
PUSH_ARTIFACTS="${PUSH_ARTIFACTS:-false}"
BUILD_WITH_DOCKER="${BUILD_WITH_DOCKER:-auto}"
DOCKER_BUILD_IMAGE="${DOCKER_BUILD_IMAGE:-starry-api-artifact-builder}"

log() {
  printf '[%s] %s\n' "$APP_NAME" "$*"
}

fail() {
  printf '[%s] ERROR: %s\n' "$APP_NAME" "$*" >&2
  exit 1
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

project_version() {
  sed -n "s/^[[:space:]]*version = ['\"]\\([^'\"]*\\)['\"].*/\\1/p" "$ROOT_DIR/build.gradle" | head -n 1
}

assert_clean_staging_area_for_auto_commit() {
  if [[ "$COMMIT_ARTIFACTS" != "true" ]]; then
    return
  fi
  git -C "$ROOT_DIR" diff --cached --quiet \
    || fail "Staged changes already exist. Commit or unstage them before COMMIT_ARTIFACTS=true."
}

has_java() {
  command -v java >/dev/null 2>&1
}

use_docker_build() {
  case "$BUILD_WITH_DOCKER" in
    true)
      return 0
      ;;
    false)
      return 1
      ;;
    auto)
      ! has_java
      ;;
    *)
      fail "BUILD_WITH_DOCKER must be auto, true, or false."
      ;;
  esac
}

build_with_gradle() {
  if [[ "$RUN_TESTS" == "true" ]]; then
    log "Running tests and building boot jar..."
    ./gradlew test :service:bootJar
  else
    log "Building boot jar..."
    ./gradlew :service:bootJar
  fi
}

build_with_docker() {
  command -v docker >/dev/null 2>&1 \
    || fail "Java is not available and Docker was not found. Install JDK 25 or Docker, or set JAVA_HOME."

  local image_tag container_id
  image_tag="${DOCKER_BUILD_IMAGE}:$(git rev-parse --short=12 HEAD 2>/dev/null || printf latest)"
  log "Java is not available locally; building artifact with Docker image ${image_tag}..."
  docker build --target build -t "$image_tag" .

  container_id="$(docker create "$image_tag" sh -c 'chmod +x gradlew && ./gradlew --no-daemon test :service:bootJar -PskipWebuiBuild=true')"
  trap 'docker rm -f "$container_id" >/dev/null 2>&1 || true' RETURN

  if [[ "$RUN_TESTS" == "true" ]]; then
    log "Running tests and bootJar inside Docker..."
    docker start -a "$container_id"
  else
    log "Skipping tests; copying Docker-built boot jar..."
  fi

  rm -rf service/build/libs
  mkdir -p service/build/libs
  docker cp "${container_id}:/workspace/service/build/libs/." service/build/libs/
  docker rm "$container_id" >/dev/null
  trap - RETURN
}

main() {
  cd "$ROOT_DIR"

  command -v git >/dev/null 2>&1 || fail "git is required."
  [[ -x ./gradlew ]] || fail "gradlew is missing or not executable."
  assert_clean_staging_area_for_auto_commit

  log "This script is for developers/CI to refresh repository artifacts."
  log "Do not run it on the server. Servers should use scripts/install-server.sh."

  local version branch commit built_at java_version dirty jar_path artifact_path checksum size_bytes checksum_path info_path
  version="${VERSION:-$(project_version)}"
  version="${version:-0.1.0-SNAPSHOT}"
  branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || printf 'unknown')"
  commit="$(git rev-parse HEAD 2>/dev/null || printf 'unknown')"
  built_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  if has_java; then
    java_version="$(java -version 2>&1 | head -n 1 || true)"
  else
    java_version="docker:${DOCKER_BUILD_IMAGE}"
  fi
  if git status --porcelain --untracked-files=all | grep -q .; then
    dirty=true
    log "Working tree has uncommitted changes; build-info.json will record dirty=true."
  else
    dirty=false
  fi

  if use_docker_build; then
    build_with_docker
  else
    has_java || fail "Java is not available. Install JDK 25, set JAVA_HOME, or run with BUILD_WITH_DOCKER=true."
    build_with_gradle
  fi

  jar_path="$(find service/build/libs -maxdepth 1 -type f -name 'service-*.jar' ! -name '*-plain.jar' | sort | tail -n 1)"
  [[ -n "$jar_path" && -f "$jar_path" ]] || fail "Boot jar was not found in service/build/libs."

  mkdir -p "$ARTIFACT_DIR"
  artifact_path="${ARTIFACT_DIR}/${ARTIFACT_NAME}"
  checksum_path="${artifact_path}.sha256"
  info_path="${ARTIFACT_DIR}/build-info.json"

  cp "$jar_path" "$artifact_path"
  checksum="$(sha256_file "$artifact_path")"
  size_bytes="$(wc -c < "$artifact_path" | tr -d '[:space:]')"
  printf '%s  %s\n' "$checksum" "$ARTIFACT_NAME" > "$checksum_path"

  cat > "$info_path" <<EOF
{
  "artifact": "$(json_escape "$ARTIFACT_NAME")",
  "version": "$(json_escape "$version")",
  "branch": "$(json_escape "$branch")",
  "commit": "$(json_escape "$commit")",
  "dirty": ${dirty},
  "builtAt": "$(json_escape "$built_at")",
  "sourceJar": "$(json_escape "$jar_path")",
  "sizeBytes": ${size_bytes},
  "sha256": "$(json_escape "$checksum")",
  "java": "$(json_escape "$java_version")"
}
EOF

  log "Updated ${artifact_path}"
  log "SHA256 ${checksum}"

  if [[ "$COMMIT_ARTIFACTS" == "true" ]]; then
    git add "$artifact_path" "$checksum_path" "$info_path" "$ARTIFACT_DIR/README.md"
    if git diff --cached --quiet; then
      log "No artifact changes to commit."
    else
      git commit -m "chore: publish prebuilt artifact for ${commit:0:12}"
    fi
    if [[ "$PUSH_ARTIFACTS" == "true" ]]; then
      git push origin "$branch"
    fi
  else
    log "Review and commit the artifact files:"
    log "  git add ${ARTIFACT_DIR}"
    log "  git commit -m \"chore: publish prebuilt artifact\""
  fi
}

main "$@"
