#!/usr/bin/env bash
set -euo pipefail
export COPYFILE_DISABLE=1

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INSTALLER="${ROOT_DIR}/scripts/install-server.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/starry-api-installer-test.XXXXXX")"

cleanup() {
  rm -rf -- "$TEST_ROOT"
}
trap cleanup EXIT

fail_test() {
  printf '[install-server-test] ERROR: %s\n' "$*" >&2
  exit 1
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

prepare_existing_deployment() {
  local case_dir="$1"
  local env_kind="$2"

  mkdir -p "$case_dir/app" "$case_dir/postgres-init" "$case_dir/runtime" "$case_dir/input"
  printf 'old-jar\n' > "$case_dir/app/starry-api.jar"
  printf 'old-compose\n' > "$case_dir/docker-compose.artifact.yml"
  printf 'old-init\n' > "$case_dir/postgres-init/001-starry-api-roles.sh"
  printf 'old-recovery-tool\n' > "$case_dir/runtime/recover-data-management.sh"
  chmod 0555 "$case_dir/runtime/recover-data-management.sh"
  printf '%s  recover-data-management.sh\n' \
    "$(sha256_file "$case_dir/runtime/recover-data-management.sh")" \
    > "$case_dir/runtime/recover-data-management.sh.sha256"
  if [[ "$env_kind" == "legacy" ]]; then
    printf 'LEGACY_DATABASE_PASSWORD=old-password\n' > "$case_dir/.env"
  else
    cat > "$case_dir/.env" <<'EOF'
HOST_BIND_ADDR=127.0.0.1
HOST_HTTP_PORT=18080
SERVER_PORT=8080
POSTGRES_BOOTSTRAP_PASSWORD=old-bootstrap-password
POSTGRES_MIGRATION_PASSWORD=old-migration-password
POSTGRES_PASSWORD=old-application-password
EOF
  fi

  cp "$case_dir/app/starry-api.jar" "$case_dir/expected.jar"
  cp "$case_dir/docker-compose.artifact.yml" "$case_dir/expected-compose.yml"
  cp "$case_dir/postgres-init/001-starry-api-roles.sh" "$case_dir/expected-init.sh"
  cp "$case_dir/runtime/recover-data-management.sh" "$case_dir/expected-recovery.sh"
  cp "$case_dir/runtime/recover-data-management.sh.sha256" "$case_dir/expected-recovery.sh.sha256"
  cp "$case_dir/.env" "$case_dir/expected.env"
}

prepare_new_artifact() {
  local case_dir="$1"
  local checksum

  mkdir -p "$case_dir/input"
  printf 'new-jar\n' > "$case_dir/input/starry-api.jar"
  checksum="$(sha256_file "$case_dir/input/starry-api.jar")"
  printf '%s  starry-api.jar\n' "$checksum" > "$case_dir/input/starry-api.jar.sha256"
  printf 'new-recovery-tool\n' > "$case_dir/input/recover-data-management.sh"
  checksum="$(sha256_file "$case_dir/input/recover-data-management.sh")"
  printf '%s  recover-data-management.sh\n' "$checksum" \
    > "$case_dir/input/recover-data-management.sh.sha256"
}

run_failure_case() {
  local scenario="$1"
  local case_dir="$2"
  local force_env="$3"
  local status

  : > "$case_dir/commands.log"
  set +e
  INSTALL_DIR="$case_dir" \
  INSTALLER="$INSTALLER" \
  SCENARIO="$scenario" \
  TRACE_FILE="$case_dir/commands.log" \
  HEALTH_FILE="$case_dir/health-count" \
  FORCE_ENV="$force_env" \
  STARRY_INSTALL_SERVER_LIBRARY_ONLY=true \
    bash -c '
      source "$INSTALLER"
      COMPOSE_CMD=(mock-compose)

      root_cmd() {
        local rendered="$*"
        printf "%s\n" "$rendered" >> "$TRACE_FILE"
        if [[ "$SCENARIO" == "legacy-health" ]] \
          && [[ "$rendered" == *" up -d postgres" ]]; then
          mkdir -p "$INSTALL_DIR/data/postgresql"
          printf "17\n" > "$INSTALL_DIR/data/postgresql/PG_VERSION"
        fi
        if [[ "$SCENARIO" == "postgres-up" || "$SCENARIO" == "first-install" ]] \
          && [[ "$rendered" == *" up -d postgres" ]]; then
          return 1
        fi
        return 0
      }

      wait_for_health() {
        local count=0
        if [[ -f "$HEALTH_FILE" ]]; then
          read -r count < "$HEALTH_FILE"
        fi
        count=$((count + 1))
        printf "%s\n" "$count" > "$HEALTH_FILE"
        if [[ "$SCENARIO" == "service-health" || "$SCENARIO" == "legacy-health" ]] \
          && [[ "$count" -eq 1 ]]; then
          return 1
        fi
        return 0
      }

      backup_current_artifact
      trap "deployment_exit_handler \$?" EXIT
      write_env_file
      load_runtime_env
      write_compose_file
      install_verified_recovery_tool \
        "$INSTALL_DIR/input/recover-data-management.sh" \
        "$INSTALL_DIR/input/recover-data-management.sh.sha256"
      install_verified_artifact \
        "$INSTALL_DIR/input/starry-api.jar" \
        "$INSTALL_DIR/input/starry-api.jar.sha256"
      deploy_stack
      DEPLOYMENT_COMMITTED=true
    ' > "$case_dir/output.log" 2>&1
  status=$?
  set -e

  [[ "$status" -ne 0 ]] || fail_test "$scenario unexpectedly succeeded"
}

assert_existing_deployment_restored() {
  local case_dir="$1"
  local expected_health_calls="$2"
  local rollback_commands health_calls

  cmp -s "$case_dir/app/starry-api.jar" "$case_dir/expected.jar" \
    || fail_test "application jar was not restored for $case_dir"
  cmp -s "$case_dir/docker-compose.artifact.yml" "$case_dir/expected-compose.yml" \
    || fail_test "Compose file was not restored for $case_dir"
  cmp -s "$case_dir/postgres-init/001-starry-api-roles.sh" "$case_dir/expected-init.sh" \
    || fail_test "PostgreSQL init script was not restored for $case_dir"
  if [[ -f "$case_dir/expected-recovery.sh" ]]; then
    cmp -s "$case_dir/runtime/recover-data-management.sh" "$case_dir/expected-recovery.sh" \
      || fail_test "data-management recovery tool was not restored for $case_dir"
    cmp -s "$case_dir/runtime/recover-data-management.sh.sha256" "$case_dir/expected-recovery.sh.sha256" \
      || fail_test "data-management recovery tool checksum was not restored for $case_dir"
    [[ -x "$case_dir/runtime/recover-data-management.sh" ]] \
      || fail_test "restored data-management recovery tool is not executable for $case_dir"
  else
    [[ ! -e "$case_dir/runtime/recover-data-management.sh" ]] \
      || fail_test "rollback must remove a recovery tool absent from the previous deployment"
    [[ ! -e "$case_dir/runtime/recover-data-management.sh.sha256" ]] \
      || fail_test "rollback must remove a recovery-tool checksum absent from the previous deployment"
  fi
  cmp -s "$case_dir/.env" "$case_dir/expected.env" \
    || fail_test "environment file was not restored for $case_dir"

  rollback_commands="$(grep -Ec ' up -d --build --force-recreate --remove-orphans$' "$case_dir/commands.log" || true)"
  [[ "$rollback_commands" -eq 1 ]] \
    || fail_test "expected one rollback stack recreation, found $rollback_commands for $case_dir"
  read -r health_calls < "$case_dir/health-count"
  [[ "$health_calls" -eq "$expected_health_calls" ]] \
    || fail_test "expected $expected_health_calls health checks, found $health_calls for $case_dir"
}

assert_toolless_deployment_restored() {
  local case_dir="$1"

  assert_existing_deployment_restored "$case_dir" 1
  [[ ! -e "$case_dir/runtime/recover-data-management.sh" ]] \
    || fail_test "rollback must remove a recovery tool absent from the previous deployment"
  [[ ! -e "$case_dir/runtime/recover-data-management.sh.sha256" ]] \
    || fail_test "rollback must remove a recovery-tool checksum absent from the previous deployment"
}

postgres_case="$TEST_ROOT/postgres-up"
prepare_existing_deployment "$postgres_case" legacy
prepare_new_artifact "$postgres_case"
run_failure_case postgres-up "$postgres_case" true
assert_existing_deployment_restored "$postgres_case" 1

toolless_case="$TEST_ROOT/toolless-upgrade"
prepare_existing_deployment "$toolless_case" postgresql
rm -f \
  "$toolless_case/runtime/recover-data-management.sh" \
  "$toolless_case/runtime/recover-data-management.sh.sha256" \
  "$toolless_case/expected-recovery.sh" \
  "$toolless_case/expected-recovery.sh.sha256"
prepare_new_artifact "$toolless_case"
run_failure_case postgres-up "$toolless_case" false
assert_toolless_deployment_restored "$toolless_case"

health_case="$TEST_ROOT/service-health"
prepare_existing_deployment "$health_case" postgresql
prepare_new_artifact "$health_case"
run_failure_case service-health "$health_case" false
assert_existing_deployment_restored "$health_case" 2

legacy_health_case="$TEST_ROOT/legacy-health"
prepare_existing_deployment "$legacy_health_case" legacy
prepare_new_artifact "$legacy_health_case"
run_failure_case legacy-health "$legacy_health_case" true
assert_existing_deployment_restored "$legacy_health_case" 2
[[ -f "$legacy_health_case/.env.postgresql-retry" ]] \
  || fail_test "failed legacy migration must retain the initialized PostgreSQL credentials"
cp "$legacy_health_case/.env.postgresql-retry" "$legacy_health_case/expected-retry.env"
INSTALL_DIR="$legacy_health_case" \
FORCE_ENV=true \
STARRY_INSTALL_SERVER_LIBRARY_ONLY=true \
  bash -c '
    source "$1"
    write_env_file
  ' _ "$INSTALLER"
cmp -s "$legacy_health_case/.env" "$legacy_health_case/expected-retry.env" \
  || fail_test "retry must reactivate the credentials matching the initialized PostgreSQL cluster"

first_case="$TEST_ROOT/first-install"
mkdir -p "$first_case/app" "$first_case/postgres-init" "$first_case/runtime"
prepare_new_artifact "$first_case"
run_failure_case first-install "$first_case" false
cmp -s "$first_case/app/starry-api.jar" "$first_case/input/starry-api.jar" \
  || fail_test "first-install artifact should remain available for a retry"
grep -q '^POSTGRES_BOOTSTRAP_PASSWORD=' "$first_case/.env" \
  || fail_test "first-install environment should remain available for a retry"
grep -q '^  postgres:' "$first_case/docker-compose.artifact.yml" \
  || fail_test "first-install Compose file should remain available for a retry"
grep -q '^STARRY_ACCOUNT_ALLOCATION_LEASE_RETENTION_DAYS=7$' "$first_case/.env" \
  || fail_test "first-install environment must include account-allocation retention defaults"
grep -q 'STARRY_ACCOUNT_ALLOCATION_MAINTENANCE_ENABLED:' "$first_case/docker-compose.artifact.yml" \
  || fail_test "first-install Compose must pass account-allocation maintenance settings"
grep -q -- '- ./data/documentation:/app/documentation$' "$first_case/docker-compose.artifact.yml" \
  || fail_test "first-install Compose must keep documentation attachments outside the container"
grep -q 'STARRY_DOCUMENTATION_ATTACHMENT_DIR: /app/documentation$' "$first_case/docker-compose.artifact.yml" \
  || fail_test "first-install Compose must point the service at the mounted documentation directory"

# The installer writes this Compose file when it falls back to the single jar; the publisher ships its own copy in
# the bundle. Both must describe the same stack, otherwise the result depends on which download path was taken.
compose_template() {
  awk -v fn="$2" '$0 ~ "^" fn "\\(\\)" { inside = 1; next }
    inside && /<<.EOF.$/ { body = 1; next }
    body && /^EOF$/ { exit }
    body { print }' "$1"
}
cmp -s <(compose_template "$INSTALLER" write_compose_file) \
  <(compose_template "${ROOT_DIR}/scripts/publish-artifact.sh" write_artifact_compose) \
  || fail_test "install-server.sh and publish-artifact.sh must write the same Compose file"
[[ -n "$(compose_template "$INSTALLER" write_compose_file)" ]] \
  || fail_test "could not read the installer Compose template"
first_rollback_commands="$(grep -Ec ' up -d --build --force-recreate --remove-orphans$' "$first_case/commands.log" || true)"
[[ "$first_rollback_commands" -eq 0 ]] \
  || fail_test "first install must not attempt to restore a nonexistent previous stack"
[[ ! -e "$first_case/app/starry-api.jar.previous" ]] \
  || fail_test "first install must not create a false rollback artifact"
cmp -s "$first_case/runtime/recover-data-management.sh" "$first_case/input/recover-data-management.sh" \
  || fail_test "first-install recovery tool should remain available for a retry"
[[ ! -e "$first_case/runtime/recover-data-management.sh.previous" ]] \
  || fail_test "first install must not create a false recovery-tool rollback artifact"

success_case="$TEST_ROOT/success"
mkdir -p "$success_case"
printf 'retry-secret\n' > "$success_case/.env.postgresql-retry"
: > "$success_case/commands.log"
INSTALL_DIR="$success_case" \
INSTALLER="$INSTALLER" \
TRACE_FILE="$success_case/commands.log" \
STARRY_INSTALL_SERVER_LIBRARY_ONLY=true \
  bash -c '
    source "$INSTALLER"
    COMPOSE_CMD=(mock-compose)
    root_cmd() {
      printf "%s\n" "$*" >> "$TRACE_FILE"
    }
    wait_for_health() {
      return 0
    }
    deploy_stack
  '
grep -q ' up -d --remove-orphans postgres service$' "$success_case/commands.log" \
  || fail_test "successful deployment must remove containers orphaned by the previous Compose definition"
[[ ! -e "$success_case/.env.postgresql-retry" ]] \
  || fail_test "successful deployment must remove the no-longer-needed retry credential copy"

bundle_case="$TEST_ROOT/bundle"
bundle_source="$bundle_case/source"
bundle_input="$bundle_case/input"
bundle_install="$bundle_case/install"
mkdir -p \
  "$bundle_source/app" \
  "$bundle_source/postgres-init" \
  "$bundle_source/runtime" \
  "$bundle_input" \
  "$bundle_install/app" \
  "$bundle_install/postgres-init" \
  "$bundle_install/runtime"
printf 'bundle-jar\n' > "$bundle_source/app/starry-api.jar"
printf '%s  starry-api.jar\n' "$(sha256_file "$bundle_source/app/starry-api.jar")" \
  > "$bundle_source/app/starry-api.jar.sha256"
printf 'bundle-compose\n' > "$bundle_source/docker-compose.artifact.yml"
printf 'bundle-init\n' > "$bundle_source/postgres-init/001-starry-api-roles.sh"
printf 'bundle-recovery-tool\n' > "$bundle_source/runtime/recover-data-management.sh"
printf '%s  recover-data-management.sh\n' \
  "$(sha256_file "$bundle_source/runtime/recover-data-management.sh")" \
  > "$bundle_source/runtime/recover-data-management.sh.sha256"
tar -C "$bundle_source" -czf "$bundle_input/starry-api-bundle.tar.gz" .
printf '%s  starry-api-bundle.tar.gz\n' \
  "$(sha256_file "$bundle_input/starry-api-bundle.tar.gz")" \
  > "$bundle_input/starry-api-bundle.tar.gz.sha256"
INSTALL_DIR="$bundle_install" \
BUNDLE_URL="$bundle_input/starry-api-bundle.tar.gz" \
BUNDLE_CHECKSUM_URL="$bundle_input/starry-api-bundle.tar.gz.sha256" \
STARRY_INSTALL_SERVER_LIBRARY_ONLY=true \
  bash -c '
    source "$1"
    download_file() {
      cp -- "$1" "$2"
    }
    download_bundle
  ' _ "$INSTALLER"
cmp -s "$bundle_install/runtime/recover-data-management.sh" "$bundle_source/runtime/recover-data-management.sh" \
  || fail_test "bundle installation must install the recovery tool"
cmp -s "$bundle_install/runtime/recover-data-management.sh.sha256" "$bundle_source/runtime/recover-data-management.sh.sha256" \
  || fail_test "bundle installation must install the recovery tool checksum"
[[ -x "$bundle_install/runtime/recover-data-management.sh" ]] \
  || fail_test "bundle recovery tool must be executable"

fallback_case="$TEST_ROOT/fallback"
fallback_input="$fallback_case/input"
fallback_install="$fallback_case/install"
mkdir -p \
  "$fallback_input" \
  "$fallback_install/app" \
  "$fallback_install/postgres-init" \
  "$fallback_install/runtime"
printf 'fallback-jar\n' > "$fallback_input/starry-api.jar"
printf '%s  starry-api.jar\n' "$(sha256_file "$fallback_input/starry-api.jar")" \
  > "$fallback_input/starry-api.jar.sha256"
printf 'fallback-recovery-tool\n' > "$fallback_input/recover-data-management.sh"
printf '%s  recover-data-management.sh\n' \
  "$(sha256_file "$fallback_input/recover-data-management.sh")" \
  > "$fallback_input/recover-data-management.sh.sha256"
printf '{"version":"test"}\n' > "$fallback_input/build-info.json"
INSTALL_DIR="$fallback_install" \
ARTIFACT_URL="$fallback_input/starry-api.jar" \
CHECKSUM_URL="$fallback_input/starry-api.jar.sha256" \
RECOVERY_TOOL_URL="$fallback_input/recover-data-management.sh" \
RECOVERY_TOOL_CHECKSUM_URL="$fallback_input/recover-data-management.sh.sha256" \
BUILD_INFO_URL="$fallback_input/build-info.json" \
STARRY_INSTALL_SERVER_LIBRARY_ONLY=true \
  bash -c '
    source "$1"
    download_file() {
      cp -- "$1" "$2"
    }
    download_artifact
  ' _ "$INSTALLER"
cmp -s "$fallback_install/runtime/recover-data-management.sh" "$fallback_input/recover-data-management.sh" \
  || fail_test "single-artifact fallback must install the recovery tool"
cmp -s "$fallback_install/runtime/recover-data-management.sh.sha256" "$fallback_input/recover-data-management.sh.sha256" \
  || fail_test "single-artifact fallback must install the recovery tool checksum"
[[ -x "$fallback_install/runtime/recover-data-management.sh" ]] \
  || fail_test "fallback recovery tool must be executable"

assert_bundle_rejected() {
  local archive="$1"
  local label="$2"
  local expected_message="$3"
  local status

  set +e
  STARRY_INSTALL_SERVER_LIBRARY_ONLY=true \
    bash -c '
      source "$1"
      validate_bundle_archive "$2"
    ' _ "$INSTALLER" "$archive" > "$TEST_ROOT/${label}.log" 2>&1
  status=$?
  set -e
  [[ "$status" -ne 0 ]] || fail_test "unsafe ${label} bundle was accepted"
  grep -Fq "$expected_message" "$TEST_ROOT/${label}.log" \
    || fail_test "unsafe ${label} bundle failed for an unexpected reason"
}

unsafe_bundle_dir="$TEST_ROOT/unsafe-bundles"
mkdir -p "$unsafe_bundle_dir/source"
printf 'payload\n' > "$unsafe_bundle_dir/source/payload"

ln -s payload "$unsafe_bundle_dir/source/symlink"
tar -C "$unsafe_bundle_dir/source" -czf "$unsafe_bundle_dir/symlink.tar.gz" symlink
assert_bundle_rejected \
  "$unsafe_bundle_dir/symlink.tar.gz" \
  symlink \
  "contains a link or special file"

ln "$unsafe_bundle_dir/source/payload" "$unsafe_bundle_dir/source/hardlink"
tar -C "$unsafe_bundle_dir/source" -czf "$unsafe_bundle_dir/hardlink.tar.gz" payload hardlink
assert_bundle_rejected \
  "$unsafe_bundle_dir/hardlink.tar.gz" \
  hardlink \
  "contains a link or special file"

mkfifo "$unsafe_bundle_dir/source/fifo"
tar -C "$unsafe_bundle_dir/source" -czf "$unsafe_bundle_dir/fifo.tar.gz" fifo
assert_bundle_rejected \
  "$unsafe_bundle_dir/fifo.tar.gz" \
  special-file \
  "contains a link or special file"

tar -Pczf "$unsafe_bundle_dir/absolute.tar.gz" "$unsafe_bundle_dir/source/payload"
assert_bundle_rejected \
  "$unsafe_bundle_dir/absolute.tar.gz" \
  absolute-path \
  "contains an absolute path"

if tar --version 2>/dev/null | grep -q 'GNU tar'; then
  tar -C "$unsafe_bundle_dir/source" \
    --transform='s|^payload$|../escape|' \
    -czf "$unsafe_bundle_dir/traversal.tar.gz" payload
else
  tar -C "$unsafe_bundle_dir/source" \
    -s ',^payload$,../escape,' \
    -czf "$unsafe_bundle_dir/traversal.tar.gz" payload
fi
assert_bundle_rejected \
  "$unsafe_bundle_dir/traversal.tar.gz" \
  parent-path \
  "contains a parent-directory path"

printf '[install-server-test] rollback, bundle, and fallback scenarios passed\n'
