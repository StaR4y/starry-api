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
  local enable_mihomo="${4:-false}"
  local status

  : > "$case_dir/commands.log"
  set +e
  INSTALL_DIR="$case_dir" \
  INSTALLER="$INSTALLER" \
  SCENARIO="$scenario" \
  TRACE_FILE="$case_dir/commands.log" \
  HEALTH_FILE="$case_dir/health-count" \
  FORCE_ENV="$force_env" \
  ENABLE_MIHOMO="$enable_mihomo" \
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
if grep -Eq '^(COMPOSE_PROFILES|STARRY_EGRESS_[A-Z_]*|MIHOMO_[A-Z_]*)=' "$first_case/.env"; then
  fail_test "an install with ENABLE_MIHOMO=false must not add any Mihomo setting to .env"
fi
if grep -qi 'mihomo' "$first_case/commands.log"; then
  fail_test "an install with ENABLE_MIHOMO=false must not mention the mihomo service in Compose commands"
fi

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
mkdir -p "$bundle_source/docker/egress"
printf 'bundle-mihomo-entrypoint\n' > "$bundle_source/docker/egress/mihomo-entrypoint.sh"
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
cmp -s "$bundle_install/docker/egress/mihomo-entrypoint.sh" "$bundle_source/docker/egress/mihomo-entrypoint.sh" \
  || fail_test "bundle installation must install the Mihomo entrypoint the Compose file mounts"

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

# --- Optional Mihomo component ---------------------------------------------------------------------------------------
#
# ENABLE_MIHOMO is auto (default), true or false. Mihomo is deployed by COMPOSE_PROFILES=mihomo plus a controller secret
# in .env; the installer never writes the backend switch, because an administrator uses the console for that.

# Runs one installer function in library mode against an install directory; stdout and stderr land in $output_file.
# An ENABLE_MIHOMO of "default" leaves the variable unset, as it is when the installer is run without it.
run_installer_function() {
  local install_dir="$1"
  local enable_mihomo="$2"
  local output_file="$3"
  local function_name="$4"
  local status
  local -a mihomo_env=("ENABLE_MIHOMO=${enable_mihomo}")

  [[ "$enable_mihomo" != "default" ]] || mihomo_env=(-u ENABLE_MIHOMO)
  set +e
  env "${mihomo_env[@]}" \
  INSTALL_DIR="$install_dir" \
  FORCE_ENV=false \
  STARRY_INSTALL_SERVER_LIBRARY_ONLY=true \
    bash -c 'source "$1"; "$2"' _ "$INSTALLER" "$function_name" > "$output_file" 2>&1
  status=$?
  set -e
  return "$status"
}

env_count() {
  grep -c -E "^$2=" "$1" || true
}

env_value() {
  grep -E "^$2=" "$1" | tail -n 1 | cut -d= -f2-
}

# Deployed: the profile (which starts the container) and the secret (which makes Mihomo available), each assigned once.
assert_mihomo_deployed_env() {
  local env_file="$1"
  local label="$2"
  local secret

  [[ "$(env_count "$env_file" COMPOSE_PROFILES)" -eq 1 ]] \
    || fail_test "$label: COMPOSE_PROFILES must be assigned exactly once"
  [[ "$(env_count "$env_file" STARRY_EGRESS_CONTROLLER_SECRET)" -eq 1 ]] \
    || fail_test "$label: STARRY_EGRESS_CONTROLLER_SECRET must be assigned exactly once"
  secret="$(env_value "$env_file" STARRY_EGRESS_CONTROLLER_SECRET)"
  [[ "$secret" =~ ^[A-Za-z0-9_-]{32,}$ ]] \
    || fail_test "$label: the controller secret must be long and accepted by the Mihomo entrypoint"
}

assert_no_mihomo_env() {
  if grep -Eq '^(COMPOSE_PROFILES|STARRY_EGRESS_[A-Z_]*|MIHOMO_[A-Z_]*)=' "$1"; then
    fail_test "$2: no Mihomo setting may be in .env"
  fi
}

file_mode() {
  ls -ld "$1" | cut -c1-10
}

# The default is auto.
default_value="$(env -u ENABLE_MIHOMO STARRY_INSTALL_SERVER_LIBRARY_ONLY=true bash -c 'source "$1"; printf "%s" "$ENABLE_MIHOMO"' _ "$INSTALLER")"
[[ "$default_value" == "auto" ]] || fail_test "ENABLE_MIHOMO must default to auto, not '${default_value}'"

# A fresh install deploys Mihomo by default (auto): the profile and a generated secret, and not the backend switch.
mihomo_fresh="$TEST_ROOT/mihomo-fresh"
mkdir -p "$mihomo_fresh"
run_installer_function "$mihomo_fresh" default "$mihomo_fresh.log" write_env_file \
  || fail_test "a fresh install with the default ENABLE_MIHOMO failed: $(cat "$mihomo_fresh.log")"
assert_mihomo_deployed_env "$mihomo_fresh/.env" "fresh install (default)"
[[ "$(env_value "$mihomo_fresh/.env" COMPOSE_PROFILES)" == "mihomo" ]] \
  || fail_test "fresh install: COMPOSE_PROFILES must select the mihomo profile"
[[ "$(env_count "$mihomo_fresh/.env" STARRY_EGRESS_MIHOMO_ENABLED)" -eq 0 ]] \
  || fail_test "fresh install: the installer must leave the backend switch to the console"
grep -q '^POSTGRES_BOOTSTRAP_PASSWORD=' "$mihomo_fresh/.env" \
  || fail_test "fresh install with Mihomo must still write the database settings"
[[ "$(file_mode "$mihomo_fresh/.env")" == "-rw-------" ]] \
  || fail_test "the .env file with the Mihomo secret must stay private"
if grep -Fq "$(env_value "$mihomo_fresh/.env" STARRY_EGRESS_CONTROLLER_SECRET)" "$mihomo_fresh.log"; then
  fail_test "the controller secret must never be printed"
fi
[[ ! -e "$mihomo_fresh/.env.previous" ]] \
  || fail_test "a fresh install has no previous .env to keep"
if ls "$mihomo_fresh"/.env.incoming.* >/dev/null 2>&1; then
  fail_test "a fresh install must not leave temporary .env files behind"
fi

# auto spelled out and true give a fresh install the same Mihomo settings; false leaves them out.
mihomo_fresh_auto="$TEST_ROOT/mihomo-fresh-auto"
mkdir -p "$mihomo_fresh_auto"
run_installer_function "$mihomo_fresh_auto" auto "$mihomo_fresh_auto.log" write_env_file \
  || fail_test "a fresh install with ENABLE_MIHOMO=auto failed: $(cat "$mihomo_fresh_auto.log")"
assert_mihomo_deployed_env "$mihomo_fresh_auto/.env" "fresh install (auto)"
mihomo_fresh_true="$TEST_ROOT/mihomo-fresh-true"
mkdir -p "$mihomo_fresh_true"
run_installer_function "$mihomo_fresh_true" true "$mihomo_fresh_true.log" write_env_file \
  || fail_test "a fresh install with ENABLE_MIHOMO=true failed: $(cat "$mihomo_fresh_true.log")"
assert_mihomo_deployed_env "$mihomo_fresh_true/.env" "fresh install (true)"
[[ "$(env_count "$mihomo_fresh_true/.env" STARRY_EGRESS_MIHOMO_ENABLED)" -eq 0 ]] \
  || fail_test "fresh install (true): the installer must leave the backend switch to the console"
mihomo_default="$TEST_ROOT/mihomo-default"
mkdir -p "$mihomo_default"
run_installer_function "$mihomo_default" false "$mihomo_default.log" write_env_file \
  || fail_test "a fresh install with ENABLE_MIHOMO=false failed: $(cat "$mihomo_default.log")"
assert_no_mihomo_env "$mihomo_default/.env" "fresh install (false)"

# An existing .env is not changed by auto or false, so an upgrade never gains a container silently.
mihomo_untouched="$TEST_ROOT/mihomo-untouched"
prepare_existing_deployment "$mihomo_untouched" postgresql
for untouched_option in default auto false; do
  run_installer_function "$mihomo_untouched" "$untouched_option" "$mihomo_untouched.log" write_env_file \
    || fail_test "ENABLE_MIHOMO=${untouched_option} on an existing .env failed: $(cat "$mihomo_untouched.log")"
  cmp -s "$mihomo_untouched/.env" "$mihomo_untouched/expected.env" \
    || fail_test "ENABLE_MIHOMO=${untouched_option} must not change an existing .env"
  [[ ! -e "$mihomo_untouched/.env.previous" ]] \
    || fail_test "ENABLE_MIHOMO=${untouched_option} must not rewrite .env.previous"
done
run_installer_function "$mihomo_untouched" auto "$mihomo_untouched.log" write_env_file
grep -q 'ENABLE_MIHOMO=true' "$mihomo_untouched.log" \
  || fail_test "an upgrade that does not deploy Mihomo must say how to add it"

# true adds the profile and the secret to an existing .env, keeps the old file as .env.previous and does not touch the rest.
mihomo_existing="$TEST_ROOT/mihomo-existing"
prepare_existing_deployment "$mihomo_existing" postgresql
chmod 0644 "$mihomo_existing/.env"
run_installer_function "$mihomo_existing" true "$mihomo_existing.log" write_env_file \
  || fail_test "adding Mihomo to an existing .env failed: $(cat "$mihomo_existing.log")"
assert_mihomo_deployed_env "$mihomo_existing/.env" "existing .env"
[[ "$(env_count "$mihomo_existing/.env" STARRY_EGRESS_MIHOMO_ENABLED)" -eq 0 ]] \
  || fail_test "adding Mihomo must not write the backend switch"
cmp -s "$mihomo_existing/.env.previous" "$mihomo_existing/expected.env" \
  || fail_test "adding Mihomo must keep the untouched .env as .env.previous"
original_lines="$(wc -l < "$mihomo_existing/expected.env" | tr -d '[:space:]')"
head -n "$original_lines" "$mihomo_existing/.env" | cmp -s - "$mihomo_existing/expected.env" \
  || fail_test "adding Mihomo must only append to an existing .env"
[[ "$(env_value "$mihomo_existing/.env" POSTGRES_PASSWORD)" == "old-application-password" ]] \
  || fail_test "adding Mihomo must keep the database credentials"
[[ "$(file_mode "$mihomo_existing/.env")" == "-rw-------" ]] \
  || fail_test "the updated .env must be private"
if grep -Fq "$(env_value "$mihomo_existing/.env" STARRY_EGRESS_CONTROLLER_SECRET)" "$mihomo_existing.log"; then
  fail_test "the controller secret must never be printed when updating an existing .env"
fi
if ls "$mihomo_existing"/.env.incoming.* >/dev/null 2>&1; then
  fail_test "adding Mihomo must not leave temporary .env files behind"
fi

# Running it again adds nothing, whichever option: no duplicate keys, same secret, same file.
cp "$mihomo_existing/.env" "$mihomo_existing/after-first.env"
for repeat_option in true auto false default; do
  run_installer_function "$mihomo_existing" "$repeat_option" "$mihomo_existing.log" write_env_file \
    || fail_test "repeating with ENABLE_MIHOMO=${repeat_option} failed: $(cat "$mihomo_existing.log")"
  cmp -s "$mihomo_existing/.env" "$mihomo_existing/after-first.env" \
    || fail_test "ENABLE_MIHOMO=${repeat_option} must leave a deployment that has Mihomo unchanged"
  assert_mihomo_deployed_env "$mihomo_existing/.env" "repeated run (${repeat_option})"
done
cmp -s "$mihomo_existing/.env.previous" "$mihomo_existing/expected.env" \
  || fail_test "a repeated run must keep the first .env.previous"

# A deployment from before the console switch (profile, switch and secret in .env) keeps all three, under every option.
mihomo_legacy="$TEST_ROOT/mihomo-legacy"
prepare_existing_deployment "$mihomo_legacy" postgresql
cat >> "$mihomo_legacy/.env" <<'EOF'
COMPOSE_PROFILES=mihomo
STARRY_EGRESS_MIHOMO_ENABLED=true
STARRY_EGRESS_CONTROLLER_SECRET=Legacy_secret-1234567890abcdef12345678
EOF
cp "$mihomo_legacy/.env" "$mihomo_legacy/expected-legacy.env"
for legacy_option in default auto true false; do
  run_installer_function "$mihomo_legacy" "$legacy_option" "$mihomo_legacy.log" write_env_file \
    || fail_test "ENABLE_MIHOMO=${legacy_option} on a legacy Mihomo .env failed: $(cat "$mihomo_legacy.log")"
  cmp -s "$mihomo_legacy/.env" "$mihomo_legacy/expected-legacy.env" \
    || fail_test "ENABLE_MIHOMO=${legacy_option} must keep STARRY_EGRESS_MIHOMO_ENABLED=true and the rest as they were"
done
[[ "$(env_value "$mihomo_legacy/.env" STARRY_EGRESS_MIHOMO_ENABLED)" == "true" ]] \
  || fail_test "the legacy switch is the initial value of the console switch and must stay"
[[ ! -e "$mihomo_legacy/.env.previous" ]] \
  || fail_test "nothing changed, so nothing is kept as .env.previous"

# Settings that are already partly there are merged, not duplicated; the existing secret and switch are kept.
mihomo_merge="$TEST_ROOT/mihomo-merge"
prepare_existing_deployment "$mihomo_merge" postgresql
cat >> "$mihomo_merge/.env" <<'EOF'
COMPOSE_PROFILES="monitoring"
STARRY_EGRESS_MIHOMO_ENABLED=false
STARRY_EGRESS_CONTROLLER_SECRET=Existing_secret-1234567890abcdef
EOF
run_installer_function "$mihomo_merge" true "$mihomo_merge.log" write_env_file \
  || fail_test "merging Mihomo settings failed: $(cat "$mihomo_merge.log")"
assert_mihomo_deployed_env "$mihomo_merge/.env" "merged .env"
[[ "$(env_value "$mihomo_merge/.env" COMPOSE_PROFILES)" == "monitoring,mihomo" ]] \
  || fail_test "merging must keep the other Compose profiles and add mihomo"
[[ "$(env_value "$mihomo_merge/.env" STARRY_EGRESS_CONTROLLER_SECRET)" == "Existing_secret-1234567890abcdef" ]] \
  || fail_test "an existing controller secret must be kept"
[[ "$(env_value "$mihomo_merge/.env" STARRY_EGRESS_MIHOMO_ENABLED)" == "false" ]] \
  || fail_test "merging must not turn an existing backend switch on"

# A secret the Mihomo entrypoint would refuse is rejected before anything changes.
mihomo_badsecret="$TEST_ROOT/mihomo-badsecret"
prepare_existing_deployment "$mihomo_badsecret" postgresql
printf 'STARRY_EGRESS_CONTROLLER_SECRET=bad secret$value\n' >> "$mihomo_badsecret/.env"
cp "$mihomo_badsecret/.env" "$mihomo_badsecret/expected-bad.env"
if run_installer_function "$mihomo_badsecret" true "$mihomo_badsecret.log" write_env_file; then
  fail_test "an unusable controller secret must be rejected"
fi
grep -Fq 'STARRY_EGRESS_CONTROLLER_SECRET' "$mihomo_badsecret.log" \
  || fail_test "the rejection must name the offending setting"
if grep -Fq 'bad secret' "$mihomo_badsecret.log"; then
  fail_test "a rejected secret must not be echoed"
fi
cmp -s "$mihomo_badsecret/.env" "$mihomo_badsecret/expected-bad.env" \
  || fail_test "a rejected secret must leave .env untouched"
[[ ! -e "$mihomo_badsecret/.env.previous" ]] \
  || fail_test "a rejected secret must not rewrite .env.previous"

# The credentials retained after a failed first deployment are reused as they are by auto, and get Mihomo with true.
mihomo_retry="$TEST_ROOT/mihomo-retry"
prepare_existing_deployment "$mihomo_retry" postgresql
mkdir -p "$mihomo_retry/data/postgresql"
printf '17\n' > "$mihomo_retry/data/postgresql/PG_VERSION"
cp "$mihomo_retry/.env" "$mihomo_retry/.env.postgresql-retry"
retry_run() {
  local option="$1"
  INSTALL_DIR="$mihomo_retry" ENABLE_MIHOMO="$option" FORCE_ENV=true STARRY_INSTALL_SERVER_LIBRARY_ONLY=true \
    bash -c 'source "$1"; write_env_file' _ "$INSTALLER" > "$mihomo_retry.log" 2>&1
}
retry_run auto || fail_test "reusing retained credentials with auto failed: $(cat "$mihomo_retry.log")"
cmp -s "$mihomo_retry/.env" "$mihomo_retry/expected.env" \
  || fail_test "auto must reuse retained credentials as they are"
retry_run true || fail_test "reusing retained credentials with true failed: $(cat "$mihomo_retry.log")"
assert_mihomo_deployed_env "$mihomo_retry/.env" "retained credentials with true"

# The option itself is validated.
for good_option in auto true false; do
  run_installer_function "$mihomo_fresh" "$good_option" "$TEST_ROOT/mihomo-option.log" validate_enable_mihomo \
    || fail_test "ENABLE_MIHOMO=${good_option} must be accepted"
done
if run_installer_function "$mihomo_fresh" maybe "$TEST_ROOT/mihomo-option.log" validate_enable_mihomo; then
  fail_test "ENABLE_MIHOMO must be auto, true or false"
fi
grep -q 'auto, true or false' "$TEST_ROOT/mihomo-option.log" \
  || fail_test "the rejection must list the accepted values"

# The state shown in the final summary follows .env.
mihomo_state_of() {
  run_installer_function "$1" false "$1.state" mihomo_state_line || fail_test "could not read the Mihomo state"
  cat "$1.state"
}
[[ "$(mihomo_state_of "$mihomo_default")" == "not deployed"* ]] || fail_test "summary must report an install without Mihomo as not deployed"
mihomo_state_text="$(mihomo_state_of "$mihomo_fresh")"
[[ "$mihomo_state_text" == "deployed - switch it on in the console under 系统设置 → 出站代理"* ]] \
  || fail_test "summary must report a deployed Mihomo and where to switch it on, got: ${mihomo_state_text}"
[[ "$mihomo_state_text" != *STARRY_EGRESS_MIHOMO_ENABLED* ]] \
  || fail_test "a fresh deployment is not switched on by .env, so the summary must not say so"
[[ "$(mihomo_state_of "$mihomo_legacy")" == "deployed - "*"STARRY_EGRESS_MIHOMO_ENABLED=true in .env keeps it on"* ]] \
  || fail_test "summary must say that a legacy switch in .env keeps Mihomo on"
[[ "$(mihomo_state_of "$mihomo_untouched")" == "not deployed (rerun with ENABLE_MIHOMO=true"* ]] \
  || fail_test "summary must tell how to deploy Mihomo when it is not"
mihomo_partial="$TEST_ROOT/mihomo-partial"
mkdir -p "$mihomo_partial"
printf 'COMPOSE_PROFILES=mihomo\n' > "$mihomo_partial/.env"
[[ "$(mihomo_state_of "$mihomo_partial")" == INCOMPLETE* ]] || fail_test "summary must flag a profile without a secret"
printf 'STARRY_EGRESS_CONTROLLER_SECRET=Only_a_secret-1234567890abcdef12\n' > "$mihomo_partial/.env"
[[ "$(mihomo_state_of "$mihomo_partial")" == INCOMPLETE* ]] || fail_test "summary must flag a secret without a profile"

# Deployment: the profile's service is not started by naming other services, so the installer names it and recreates it
# with the service it shares a network namespace with.
mihomo_deploy="$TEST_ROOT/mihomo-deploy"
mkdir -p "$mihomo_deploy"
cp "$mihomo_fresh/.env" "$mihomo_deploy/.env"
: > "$mihomo_deploy/commands.log"
INSTALL_DIR="$mihomo_deploy" \
INSTALLER="$INSTALLER" \
TRACE_FILE="$mihomo_deploy/commands.log" \
STARRY_INSTALL_SERVER_LIBRARY_ONLY=true \
  bash -c '
    source "$INSTALLER"
    COMPOSE_CMD=(mock-compose)
    root_cmd() { printf "%s\n" "$*" >> "$TRACE_FILE"; }
    wait_for_health() { return 0; }
    deploy_stack
  ' > "$mihomo_deploy/output.log" 2>&1
grep -q ' up -d --build --force-recreate service mihomo$' "$mihomo_deploy/commands.log" \
  || fail_test "with Mihomo deployed the service and mihomo must be recreated together"
grep -q ' up -d --remove-orphans postgres service mihomo$' "$mihomo_deploy/commands.log" \
  || fail_test "with Mihomo deployed the final up must include mihomo"
grep -q '^Mihomo egress: *deployed - switch it on in the console' "$mihomo_deploy/output.log" \
  || fail_test "the deployment summary must say Mihomo is deployed and how to switch it on"
if grep -Fq "$(env_value "$mihomo_deploy/.env" STARRY_EGRESS_CONTROLLER_SECRET)" "$mihomo_deploy/output.log" "$mihomo_deploy/commands.log"; then
  fail_test "the deployment must not print the controller secret"
fi
if grep -q '^Mihomo egress: *not deployed' "$mihomo_deploy/output.log"; then
  fail_test "a deployed Mihomo must not be summarised as not deployed"
fi
success_summary="$TEST_ROOT/success-summary.log"
INSTALL_DIR="$success_case" \
STARRY_INSTALL_SERVER_LIBRARY_ONLY=true \
  bash -c '
    source "$1"
    COMPOSE_CMD=(mock-compose)
    root_cmd() { :; }
    wait_for_health() { return 0; }
    deploy_stack
  ' _ "$INSTALLER" > "$success_summary" 2>&1
grep -q '^Mihomo egress: *not deployed (rerun with ENABLE_MIHOMO=true' "$success_summary" \
  || fail_test "the deployment summary must say Mihomo is not deployed and how to deploy it"

# A failed deployment that had started Mihomo restores the old .env, and a Mihomo container left over from the failed
# attempt is removed explicitly because Compose does not treat an inactive profile's container as an orphan.
mihomo_rollback="$TEST_ROOT/mihomo-rollback"
prepare_existing_deployment "$mihomo_rollback" postgresql
prepare_new_artifact "$mihomo_rollback"
run_failure_case service-health "$mihomo_rollback" false true
assert_existing_deployment_restored "$mihomo_rollback" 2
grep -q ' up -d --build --force-recreate service mihomo$' "$mihomo_rollback/commands.log" \
  || fail_test "the failed attempt must have started Mihomo with the service"
grep -q ' rm -sf mihomo$' "$mihomo_rollback/commands.log" \
  || fail_test "rolling back to a deployment without Mihomo must remove the Mihomo container"
if grep -q 'mihomo' "$mihomo_rollback/.env"; then
  fail_test "the restored .env must not mention Mihomo"
fi

# ensure_mihomo_support: supplies the entrypoint the container mounts, and deals with a Compose file that has no
# mihomo service according to who chose Mihomo.
mihomo_support="$TEST_ROOT/mihomo-support"
mkdir -p "$mihomo_support/input"
cp "$mihomo_fresh/.env" "$mihomo_support/.env"
printf 'services:\n  service:\n    image: x\n' > "$mihomo_support/docker-compose.artifact.yml"
if run_installer_function "$mihomo_support" false "$mihomo_support.log" ensure_mihomo_support; then
  fail_test "a bundle without the mihomo service must be refused when .env selects Mihomo"
fi
grep -q 'does not define the mihomo service' "$mihomo_support.log" \
  || fail_test "the refusal must explain that the bundle has no mihomo service"
printf '#!/bin/sh\necho entrypoint\n' > "$mihomo_support/input/mihomo-entrypoint.sh"
compose_template "$INSTALLER" write_compose_file > "$mihomo_support/docker-compose.artifact.yml"
mkdir -p "$mihomo_support/docker/egress/mihomo-entrypoint.sh"   # what Docker leaves behind for a missing mount source
INSTALL_DIR="$mihomo_support" \
MIHOMO_ENTRYPOINT_URL="$mihomo_support/input/mihomo-entrypoint.sh" \
STARRY_INSTALL_SERVER_LIBRARY_ONLY=true \
  bash -c '
    source "$1"
    download_file() { cp -- "$1" "$2"; }
    ensure_mihomo_support
  ' _ "$INSTALLER" > "$mihomo_support.log" 2>&1 \
  || fail_test "ensure_mihomo_support failed: $(cat "$mihomo_support.log")"
[[ -f "$mihomo_support/docker/egress/mihomo-entrypoint.sh" ]] \
  || fail_test "the Mihomo entrypoint must be installed as a file"
cmp -s "$mihomo_support/docker/egress/mihomo-entrypoint.sh" "$mihomo_support/input/mihomo-entrypoint.sh" \
  || fail_test "the downloaded Mihomo entrypoint must be installed unchanged"
rm -f "$mihomo_support/.env"
rm -rf "$mihomo_support/docker"
run_installer_function "$mihomo_support" false "$mihomo_support.log" ensure_mihomo_support \
  || fail_test "without Mihomo the installer must not require the entrypoint or the mihomo service"
[[ ! -e "$mihomo_support/docker" ]] \
  || fail_test "without Mihomo the installer must not fetch the entrypoint"

# An older release whose bundle has no mihomo service: a fresh install that chose Mihomo on its own (auto) goes on
# without it and says so; everything else is refused, because someone asked for Mihomo.
older_release_run() {
  local install_dir="$1"
  local option="$2"
  local compose_kind="$3"
  mkdir -p "$install_dir/input"
  if [[ "$compose_kind" == "old" ]]; then
    printf 'services:\n  service:\n    image: x\n' > "$install_dir/docker-compose.artifact.yml"
  else
    compose_template "$INSTALLER" write_compose_file > "$install_dir/docker-compose.artifact.yml"
  fi
  printf '#!/bin/sh\necho entrypoint\n' > "$install_dir/input/mihomo-entrypoint.sh"
  set +e
  INSTALL_DIR="$install_dir" ENABLE_MIHOMO="$option" FORCE_ENV=false \
  MIHOMO_ENTRYPOINT_URL="$install_dir/input/mihomo-entrypoint.sh" \
  STARRY_INSTALL_SERVER_LIBRARY_ONLY=true \
    bash -c '
      source "$1"
      download_file() { cp -- "$1" "$2"; }
      write_env_file
      cp "$INSTALL_DIR/.env" "$INSTALL_DIR/env-before-support"
      ensure_mihomo_support
    ' _ "$INSTALLER" > "$install_dir.log" 2>&1
  local status=$?
  set -e
  return "$status"
}

older_auto="$TEST_ROOT/older-auto"
mkdir -p "$older_auto"
older_release_run "$older_auto" auto old \
  || fail_test "an older bundle without the mihomo service must not fail a fresh auto install: $(cat "$older_auto.log")"
assert_mihomo_deployed_env "$older_auto/env-before-support" "before the bundle was checked"
assert_no_mihomo_env "$older_auto/.env" "after skipping Mihomo for an older bundle"
grep -q '^POSTGRES_BOOTSTRAP_PASSWORD=' "$older_auto/.env" \
  || fail_test "skipping Mihomo must keep the database settings"
grep -q 'does not define the mihomo service' "$older_auto.log" \
  || fail_test "skipping Mihomo must be logged"
[[ ! -e "$older_auto/docker" ]] || fail_test "a skipped Mihomo must not fetch its entrypoint"
[[ "$(mihomo_state_of "$older_auto")" == "not deployed"* ]] \
  || fail_test "the summary must report the skipped Mihomo as not deployed"
if grep -Fq "$(env_value "$older_auto/env-before-support" STARRY_EGRESS_CONTROLLER_SECRET)" "$older_auto.log"; then
  fail_test "skipping Mihomo must not print the discarded secret"
fi

older_true="$TEST_ROOT/older-true"
mkdir -p "$older_true"
if older_release_run "$older_true" true old; then
  fail_test "ENABLE_MIHOMO=true must fail when the bundle has no mihomo service"
fi
grep -q 'does not define the mihomo service' "$older_true.log" \
  || fail_test "the refusal for ENABLE_MIHOMO=true must explain why"

older_new="$TEST_ROOT/older-with-service"
mkdir -p "$older_new"
older_release_run "$older_new" auto current \
  || fail_test "a bundle with the mihomo service must keep Mihomo on a fresh auto install: $(cat "$older_new.log")"
assert_mihomo_deployed_env "$older_new/.env" "bundle with the mihomo service"
[[ -f "$older_new/docker/egress/mihomo-entrypoint.sh" ]] \
  || fail_test "a deployed Mihomo needs its entrypoint installed"

# An existing .env that selects Mihomo is somebody's earlier choice: auto does not silently drop it either.
older_existing="$TEST_ROOT/older-existing"
prepare_existing_deployment "$older_existing" postgresql
printf 'COMPOSE_PROFILES=mihomo\nSTARRY_EGRESS_CONTROLLER_SECRET=Earlier_choice-1234567890abcdef1234\n' >> "$older_existing/.env"
if older_release_run "$older_existing" auto old; then
  fail_test "an existing Mihomo choice must not be dropped silently for an older bundle"
fi

# The three Compose definitions of the mihomo service and of the service's egress settings must not drift apart, and
# nothing that a profile-less deployment interpolates may be mandatory.
mihomo_block() {
  awk '/^  # Optional Mihomo \(Clash.Meta\)/ { on = 1 } /^networks:/ { on = 0 } on { print }' "$1"
}
template_file="$TEST_ROOT/installer-compose-template.yml"
compose_template "$INSTALLER" write_compose_file > "$template_file"
[[ -n "$(mihomo_block "$template_file")" ]] || fail_test "the installer Compose template must define the mihomo service"
cmp -s <(mihomo_block "${ROOT_DIR}/docker-compose.yml") <(mihomo_block "$template_file") \
  || fail_test "docker-compose.yml and the installer template must define the same mihomo service"
for compose_source in "${ROOT_DIR}/docker-compose.yml" "$template_file"; do
  grep -q 'profiles: \["mihomo"\]' "$compose_source" \
    || fail_test "the mihomo service must belong to the mihomo profile in $compose_source"
  grep -q 'image: metacubex/mihomo:${MIHOMO_VERSION:-v1.19.32}$' "$compose_source" \
    || fail_test "the mihomo service must use the pinned official image in $compose_source"
  grep -q 'STARRY_EGRESS_MIHOMO_ENABLED: ${STARRY_EGRESS_MIHOMO_ENABLED:-false}$' "$compose_source" \
    || fail_test "the service must pass the legacy Mihomo switch (the console switch's initial value), default false, in $compose_source"
  grep -q 'STARRY_EGRESS_CONTROLLER_SECRET: ${STARRY_EGRESS_CONTROLLER_SECRET:-}$' "$compose_source" \
    || fail_test "the service and mihomo must both receive the controller secret in $compose_source"
  if grep -E 'STARRY_EGRESS_|MIHOMO_' "$compose_source" | grep -q ':?'; then
    fail_test "Compose interpolates inactive profiles: no Mihomo setting may be mandatory in $compose_source"
  fi
done
if grep -E 'STARRY_EGRESS_TLS|STARRY_TLS_SECRET|tls-bridge' "${ROOT_DIR}/docker-compose.yml" "$template_file" >/dev/null; then
  fail_test "the TLS bridge must not be coupled to the base Compose files"
fi
[[ ! -e "${ROOT_DIR}/docker-compose.egress.yml" ]] \
  || fail_test "docker-compose.egress.yml was replaced by docker-compose.tls-bridge.yml"
grep -q 'STARRY_TLS_SECRET: ${STARRY_TLS_SECRET:?' "${ROOT_DIR}/docker-compose.tls-bridge.yml" \
  || fail_test "the opt-in TLS bridge overlay must require its secret"
if grep -vE '^[[:space:]]*#' "${ROOT_DIR}/docker-compose.tls-bridge.yml" | grep -qi 'mihomo'; then
  fail_test "the TLS bridge overlay must not depend on Mihomo"
fi


printf '[install-server-test] rollback, bundle, fallback, and Mihomo scenarios passed\n'
